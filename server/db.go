package main

import (
	"context"
	"database/sql"
	_ "embed"
	"errors"
	"fmt"
	"log"
	"net/url"
	"path/filepath"
	"sync/atomic"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaSQL string

// ErrBusy: la coda delle scritture è piena; il client deve riprovare più tardi.
var ErrBusy = errors.New("server occupato")

// Store riunisce le due connessioni al database:
//   - read: poche connessioni in sola lettura (WAL: non bloccano le scritture);
//   - write: UNA connessione usata solo dallo scrittore, che raggruppa le
//     scritture in transazioni a blocchi (vedi Writer).
type Store struct {
	read  *sql.DB
	write *sql.DB
	W     *Writer
}

func dsn(path string, readOnly bool) string {
	q := url.Values{}
	for _, p := range []string{
		"busy_timeout(5000)",  // se il file è occupato attende fino a 5 s invece di fallire
		"foreign_keys(1)",     // integrità e cancellazioni a cascata
		"synchronous(NORMAL)", // con WAL: niente fsync a ogni commit, solo ai checkpoint
		"cache_size(-2048)",   // 2 MB di cache per connessione
		"temp_store(MEMORY)",
		"mmap_size(0)", // nessuna mappatura: la memoria resta prevedibile
	} {
		q.Add("_pragma", p)
	}
	if readOnly {
		q.Add("_pragma", "query_only(1)")
	} else {
		q.Add("_pragma", "journal_mode(WAL)")
		q.Add("_pragma", "journal_size_limit(4194304)") // WAL troncato a 4 MB
		q.Set("_txlock", "immediate")
	}
	return "file:" + filepath.ToSlash(path) + "?" + q.Encode()
}

// OpenStore apre (o crea) il database e applica lo schema.
func OpenStore(path string, readConns int, batchDelay time.Duration, queueSize int) (*Store, error) {
	w, err := sql.Open("sqlite", dsn(path, false))
	if err != nil {
		return nil, err
	}
	w.SetMaxOpenConns(1)
	w.SetMaxIdleConns(1)
	w.SetConnMaxLifetime(0)
	if _, err := w.Exec(schemaSQL); err != nil {
		w.Close()
		return nil, fmt.Errorf("schema: %w", err)
	}
	r, err := sql.Open("sqlite", dsn(path, true))
	if err != nil {
		w.Close()
		return nil, err
	}
	if readConns < 1 {
		readConns = 1
	}
	r.SetMaxOpenConns(readConns)
	r.SetMaxIdleConns(readConns)
	r.SetConnMaxIdleTime(5 * time.Minute) // a riposo le connessioni si chiudono
	s := &Store{read: r, write: w}
	s.W = NewWriter(w, batchDelay, queueSize)
	return s, nil
}

func (s *Store) Close() {
	s.W.Close()
	// Checkpoint finale: il WAL torna nel file principale.
	if _, err := s.write.Exec("PRAGMA wal_checkpoint(TRUNCATE); PRAGMA optimize;"); err != nil {
		log.Printf("checkpoint: %v", err)
	}
	s.read.Close()
	s.write.Close()
}

/* ----------------------------- Scrittore ------------------------------ */

type job struct {
	fn   func(tx *sql.Tx) error
	done chan error
}

// Writer esegue tutte le scritture su un solo goroutine.
//
// Le richieste arrivano in coda; lo scrittore prende la prima, aspetta fino
// a [delay] raccogliendo le altre (max [maxBatch]) e le salva in UNA
// transazione: con molti invii contemporanei il disco lavora una volta sola.
// Ogni richiesta ha un SAVEPOINT, così l'errore di una non annulla le altre.
// Se la coda è piena, Do restituisce subito ErrBusy (HTTP 503 + Retry-After).
type Writer struct {
	db       *sql.DB
	jobs     chan *job
	delay    time.Duration
	maxBatch int
	stopped  chan struct{}
	batches  atomic.Int64 // transazioni eseguite (per /health e per i test)
}

func NewWriter(db *sql.DB, delay time.Duration, queueSize int) *Writer {
	if queueSize < 1 {
		queueSize = 1
	}
	w := &Writer{db: db, jobs: make(chan *job, queueSize), delay: delay, maxBatch: 64, stopped: make(chan struct{})}
	go w.loop()
	return w
}

// Do accoda fn e attende che sia salvata. fn gira dentro la transazione:
// legge e scrive con tx e vede le proprie modifiche.
func (w *Writer) Do(ctx context.Context, fn func(tx *sql.Tx) error) error {
	j := &job{fn: fn, done: make(chan error, 1)}
	select {
	case w.jobs <- j:
	default:
		return ErrBusy
	}
	releaseSlot(ctx)
	select {
	case err := <-j.done:
		return err
	case <-ctx.Done():
		// Il client se n'è andato: il lavoro verrà comunque salvato.
		return ctx.Err()
	}
}

// Pending: richieste in coda (per /health).
func (w *Writer) Pending() int { return len(w.jobs) }

// Batches: transazioni eseguite dall'avvio.
func (w *Writer) Batches() int64 { return w.batches.Load() }

func (w *Writer) Close() {
	close(w.jobs)
	<-w.stopped
}

func (w *Writer) loop() {
	defer close(w.stopped)
	for first := range w.jobs {
		batch := []*job{first}
		if w.delay > 0 {
			timer := time.NewTimer(w.delay)
		collect:
			for len(batch) < w.maxBatch {
				select {
				case j, ok := <-w.jobs:
					if !ok {
						break collect
					}
					batch = append(batch, j)
				case <-timer.C:
					break collect
				}
			}
			timer.Stop()
		}
		// Prende anche quello che è già in coda, senza attendere.
	drain:
		for len(batch) < w.maxBatch {
			select {
			case j, ok := <-w.jobs:
				if !ok {
					break drain
				}
				batch = append(batch, j)
			default:
				break drain
			}
		}
		w.run(batch)
	}
}

func (w *Writer) run(batch []*job) {
	w.batches.Add(1)
	errs := make([]error, len(batch))
	tx, err := w.db.Begin()
	if err != nil {
		for _, j := range batch {
			j.done <- err
		}
		return
	}
	for i, j := range batch {
		if _, err := tx.Exec("SAVEPOINT j"); err != nil {
			errs[i] = err
			continue
		}
		errs[i] = safeRun(j.fn, tx)
		if errs[i] != nil {
			tx.Exec("ROLLBACK TO j")
		}
		tx.Exec("RELEASE j")
	}
	if err := tx.Commit(); err != nil {
		for i := range errs {
			if errs[i] == nil {
				errs[i] = err
			}
		}
	}
	for i, j := range batch {
		j.done <- errs[i]
	}
}

func safeRun(fn func(*sql.Tx) error, tx *sql.Tx) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("errore interno: %v", r)
		}
	}()
	return fn(tx)
}
