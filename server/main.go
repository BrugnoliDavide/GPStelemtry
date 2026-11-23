// Server della community di GPStelemetry.
//
// Un solo eseguibile, database SQLite in un file, pensato per girare su un
// Raspberry Pi 4 (1 GB) insieme ad altri servizi con il minimo di risorse.
// Contratto HTTP: android/docs/API.md (endpoint sotto /v1).
package main

import (
	"context"
	"database/sql"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime/debug"
	"syscall"
	"time"
)

var trustProxyFlag bool

func main() {
	addr := flag.String("addr", "127.0.0.1:8080", "indirizzo di ascolto (dietro il proxy HTTPS)")
	data := flag.String("data", "./data", "cartella dei dati (database e foto)")
	publicURL := flag.String("public-url", "http://127.0.0.1:8080", "indirizzo pubblico del server, per i link alle foto")
	maxInflight := flag.Int("max-inflight", 8, "richieste lavorate insieme; le altre attendono")
	wait := flag.Duration("wait", 3*time.Second, "attesa massima di un posto libero prima di rispondere 503")
	batchDelay := flag.Duration("batch-delay", 500*time.Millisecond, "attesa per raggruppare le scritture in un'unica transazione")
	queue := flag.Int("queue", 256, "scritture in coda oltre le quali si risponde 503")
	readConns := flag.Int("read-conns", 2, "connessioni di lettura al database")
	rate := flag.Float64("rate", 10, "richieste al secondo per indirizzo IP")
	burst := flag.Float64("burst", 40, "picco di richieste per indirizzo IP")
	flag.BoolVar(&trustProxyFlag, "trust-proxy", false, "usa X-Forwarded-For (solo dietro un proxy fidato)")
	backup := flag.String("backup", "", "copia coerente del database in questo file, poi esce (si può fare a server acceso)")
	flag.Parse()

	if *backup != "" {
		if err := backupTo(filepath.Join(*data, "gpstelemetry.db"), *backup); err != nil {
			log.Fatal(err)
		}
		log.Printf("backup salvato in %s", *backup)
		return
	}

	// Tetto morbido alla memoria del Go runtime se non impostato da fuori (GOMEMLIMIT).
	if os.Getenv("GOMEMLIMIT") == "" {
		debug.SetMemoryLimit(48 << 20)
	}

	avatars := filepath.Join(*data, "avatars")
	if err := os.MkdirAll(avatars, 0o755); err != nil {
		log.Fatal(err)
	}
	store, err := OpenStore(filepath.Join(*data, "gpstelemetry.db"), *readConns, *batchDelay, *queue)
	if err != nil {
		log.Fatal(err)
	}
	app := &App{
		db:         store,
		avatarsDir: avatars,
		publicURL:  *publicURL,
		loginRL:    NewRateLimit(0.5, 5), // accessi con password: bcrypt costa CPU
		now:        nowMs,
	}

	var h http.Handler = app.Routes()
	h = NewLimiter(*maxInflight, *wait).Wrap(h)
	h = NewRateLimit(*rate, *burst).Wrap(trustProxyFlag, app.validToken, h)
	h = recoverer(h)

	srv := &http.Server{
		Addr:              *addr,
		Handler:           h,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	go maintenance(store)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stop
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		srv.Shutdown(ctx)
	}()

	log.Printf("GPStelemetry server in ascolto su %s (dati in %s)", *addr, *data)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
	store.Close()
	log.Print("chiuso")
}

// maintenance: ogni 6 ore aggiorna le statistiche degli indici e riporta il
// WAL nel file principale. Passa dallo scrittore, quindi non si sovrappone.
func maintenance(s *Store) {
	for range time.Tick(6 * time.Hour) {
		err := s.W.Do(context.Background(), func(tx *sql.Tx) error {
			_, err := tx.Exec(`PRAGMA optimize`)
			return err
		})
		if err != nil {
			log.Printf("manutenzione: %v", err)
		}
		if _, err := s.write.Exec(`PRAGMA wal_checkpoint(PASSIVE)`); err != nil {
			log.Printf("checkpoint: %v", err)
		}
	}
}

// backupTo: VACUUM INTO scrive una copia compatta e coerente del database
// (un'istantanea) senza fermare il server.
func backupTo(dbPath, target string) error {
	if _, err := os.Stat(dbPath); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o750); err != nil {
		return err
	}
	tmp := target + ".tmp"
	os.Remove(tmp)
	db, err := sql.Open("sqlite", dsn(dbPath, false))
	if err != nil {
		return err
	}
	defer db.Close()
	if _, err := db.Exec(`VACUUM INTO ?`, tmp); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, target)
}

func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				log.Printf("panic %s %s: %v", r.Method, r.URL.Path, v)
				writeErr(w, 500, "internal", "Errore del server, riprova più tardi")
			}
		}()
		next.ServeHTTP(w, r)
	})
}
