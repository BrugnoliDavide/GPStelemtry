package main

// "Procedure" del database. SQLite non ha procedure memorizzate: le regole
// che girano nel database sono nello schema (trigger e viste), queste sono
// le interrogazioni con nome usate dal server, tutte su indici.

import (
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"sort"
	"strings"
)

// querier: *sql.DB oppure *sql.Tx.
type querier interface {
	QueryRow(query string, args ...any) *sql.Row
	Query(query string, args ...any) (*sql.Rows, error)
	Exec(query string, args ...any) (sql.Result, error)
}

const privateDriver = "Pilota privato"

var reservedNicknames = map[string]bool{
	"admin": true, "root": true, "moderatore": true, "gpstelemetry": true,
	"support": true, "staff": true, "null": true,
}

var vehicleTypes = map[string]bool{
	"CAR": true, "MOTORCYCLE": true, "KART": true, "BICYCLE": true, "MTB": true,
	"RUNNING": true, "SCOOTER": true, "TRUCK": true, "SKI": true,
}

func newUID() string {
	b := make([]byte, 12)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

/* ------------------------------ Nickname ------------------------------ */

// ValidateNickname: stesse regole di Nickname.validate nell'app.
func ValidateNickname(raw string) (string, string) {
	n := strings.TrimSpace(raw)
	switch {
	case len(n) < 3:
		return n, "Almeno 3 caratteri"
	case len(n) > 20:
		return n, "Al massimo 20 caratteri"
	}
	for _, c := range n {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '.') {
			return n, "Solo lettere, numeri, _ e ."
		}
	}
	if strings.HasPrefix(n, ".") || strings.HasSuffix(n, ".") {
		return n, "Non può iniziare o finire con un punto"
	}
	return n, ""
}

// nicknameOwner: id del pilota che usa il nickname (maiuscole indifferenti), 0 se libero.
func nicknameOwner(db querier, n string) (int64, error) {
	var id int64
	err := db.QueryRow(`SELECT id FROM piloti WHERE nickname = ? COLLATE NOCASE`, n).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return id, err
}

type NicknameCheck struct {
	Nickname    string   `json:"nickname"`
	Available   bool     `json:"available"`
	Reason      *string  `json:"reason"`
	Suggestions []string `json:"suggestions"`
}

// PROCEDURA: il nickname è libero? (selfID = chi chiede, il proprio nome è libero per lui)
func NicknameAvailability(db querier, raw string, selfID int64) (NicknameCheck, error) {
	n, why := ValidateNickname(raw)
	res := NicknameCheck{Nickname: n, Suggestions: []string{}}
	if why != "" {
		res.Reason = &why
		return res, nil
	}
	if reservedNicknames[strings.ToLower(n)] {
		why = "Nickname riservato"
	} else {
		owner, err := nicknameOwner(db, n)
		if err != nil {
			return res, err
		}
		if owner != 0 && owner != selfID {
			why = "Già in uso"
		}
	}
	if why == "" {
		res.Available = true
		return res, nil
	}
	res.Reason = &why
	res.Suggestions = suggestNicknames(db, n)
	return res, nil
}

func suggestNicknames(db querier, base string) []string {
	if len(base) > 17 {
		base = base[:17]
	}
	base = strings.TrimRight(base, ".")
	if base == "" {
		base = "pilota"
	}
	var rnd [2]byte
	rand.Read(rnd[:])
	cands := []string{base + "_rc", fmt.Sprintf("%s%d", base, 10+int(rnd[0])%90), base + ".gp", fmt.Sprintf("%s_%d", base, 100+int(rnd[1])%900)}
	out := []string{}
	for _, c := range cands {
		if _, why := ValidateNickname(c); why != "" || reservedNicknames[strings.ToLower(c)] {
			continue
		}
		if id, err := nicknameOwner(db, c); err == nil && id == 0 {
			out = append(out, c)
		}
		if len(out) == 3 {
			break
		}
	}
	return out
}

/* ------------------------------ Tracciati ----------------------------- */

type TrackRow struct {
	ID, AuthorID                 int64
	UID, Name, Type, Fingerprint string
	Author, AuthorVisibility     string
	TotalSectors, TimedSectors   int
	NeutralMask                  int64
	OverTen                      bool
	SectorNames                  []string
	Lat, Lon                     float64
	PublishedAt, UpdatedAt       int64
}

const trackCols = `t.id, t.uid, t.autore_id, p.nickname, p.visibilita, t.nome, t.tipo, t.settori_totali,
	t.maschera_neutri, t.num_settori, t.oltre_10, t.nomi_settori, t.impronta, t.lat, t.lon, t.pubblicato_il, t.aggiornato_il`

const trackFrom = ` FROM tracciati t JOIN piloti p ON p.id = t.autore_id `

func scanTrack(sc interface{ Scan(...any) error }) (*TrackRow, error) {
	var t TrackRow
	var names string
	if err := sc.Scan(&t.ID, &t.UID, &t.AuthorID, &t.Author, &t.AuthorVisibility, &t.Name, &t.Type, &t.TotalSectors,
		&t.NeutralMask, &t.TimedSectors, &t.OverTen, &names, &t.Fingerprint, &t.Lat, &t.Lon, &t.PublishedAt, &t.UpdatedAt); err != nil {
		return nil, err
	}
	json.Unmarshal([]byte(names), &t.SectorNames)
	return &t, nil
}

func trackByUID(db querier, uid string) (*TrackRow, error) {
	t, err := scanTrack(db.QueryRow(`SELECT `+trackCols+trackFrom+`WHERE t.uid = ?`, uid))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return t, err
}

func queryTracks(db querier, where string, args ...any) ([]*TrackRow, error) {
	rows, err := db.Query(`SELECT `+trackCols+trackFrom+where, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*TrackRow
	for rows.Next() {
		t, err := scanTrack(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

/* ------------------------------ Classifiche --------------------------- */

type Entry struct {
	Rank        int     `json:"rank"`
	Driver      string  `json:"driver"`
	LapMs       int64   `json:"lapMs"`
	SectorsMs   []int64 `json:"sectorsMs"`
	SetAt       int64   `json:"setAt"`
	IsMe        bool    `json:"isMe"`
	VehicleType *string `json:"vehicleType"`
	Vehicle     *string `json:"vehicle"`
	LapID       *string `json:"lapId"`

	pilotID int64
	timed   []int64 // settori cronometrati salvati (s1..sn)
}

const resultCols = `r.uid, r.pilota_id, p.nickname, p.visibilita, r.categoria, r.veicolo,
	r.s1, r.s2, r.s3, r.s4, r.s5, r.s6, r.s7, r.s8, r.s9, r.s10, r.tempo_totale, r.impostato_il`

func scanEntry(sc interface{ Scan(...any) error }, t *TrackRow, meID int64) (*Entry, error) {
	var e Entry
	var uid, nick, vis, cat string
	var veh sql.NullString
	var s [10]sql.NullInt64
	if err := sc.Scan(&uid, &e.pilotID, &nick, &vis, &cat, &veh,
		&s[0], &s[1], &s[2], &s[3], &s[4], &s[5], &s[6], &s[7], &s[8], &s[9], &e.LapMs, &e.SetAt); err != nil {
		return nil, err
	}
	for _, v := range s {
		if v.Valid {
			e.timed = append(e.timed, v.Int64)
		}
	}
	e.LapID = &uid
	e.IsMe = meID != 0 && e.pilotID == meID
	e.Driver = nick
	if vis == "PRIVATE" && !e.IsMe {
		e.Driver = privateDriver
	}
	if cat != "" {
		e.VehicleType = &cat
	}
	if veh.Valid {
		e.Vehicle = &veh.String
	}
	e.SectorsMs = ExpandSectors(t.TotalSectors, t.NeutralMask, t.OverTen, e.timed)
	return &e, nil
}

// PROCEDURA: classifica di un tracciato. Senza categoria: miglior giro di
// ogni pilota tra tutte le sue categorie (filter nil); con filter solo quella
// categoria ("" = non indicata). Ordine: tempo, poi chi l'ha fatto prima.
// limit <= 0 = completa.
func Leaderboard(db querier, t *TrackRow, filter *string, meID int64, limit int) ([]*Entry, error) {
	q := `SELECT ` + resultCols + ` FROM risultati r JOIN piloti p ON p.id = r.pilota_id WHERE r.tracciato_id = ?`
	args := []any{t.ID}
	if filter != nil {
		q += ` AND r.categoria = ?`
		args = append(args, *filter)
	}
	q += ` ORDER BY r.tempo_totale, r.impostato_il`
	if limit > 0 && filter != nil {
		q += fmt.Sprintf(` LIMIT %d`, limit)
	}
	rows, err := db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	seen := map[int64]bool{}
	out := []*Entry{}
	for rows.Next() {
		e, err := scanEntry(rows, t, meID)
		if err != nil {
			return nil, err
		}
		if seen[e.pilotID] {
			continue // stesso pilota con un'altra categoria più lenta
		}
		seen[e.pilotID] = true
		e.Rank = len(out) + 1
		out = append(out, e)
		if limit > 0 && len(out) == limit {
			break
		}
	}
	return out, rows.Err()
}

// PROCEDURA: numero di piloti in classifica.
func DriverCount(db querier, trackID int64) (int, error) {
	var n int
	err := db.QueryRow(`SELECT count(DISTINCT pilota_id) FROM risultati WHERE tracciato_id = ?`, trackID).Scan(&n)
	return n, err
}

// PROCEDURA: tempo migliore del tracciato (assoluto, o della categoria).
func BestTime(db querier, t *TrackRow, category string, meID int64) (*Entry, error) {
	var row *sql.Row
	if category == "" {
		// Record mantenuto dai trigger: lettura diretta.
		row = db.QueryRow(`SELECT `+resultCols+` FROM tracciati t JOIN risultati r ON r.id = t.miglior_risultato_id
			JOIN piloti p ON p.id = r.pilota_id WHERE t.id = ?`, t.ID)
	} else {
		row = db.QueryRow(`SELECT `+resultCols+` FROM risultati r JOIN piloti p ON p.id = r.pilota_id
			WHERE r.tracciato_id = ? AND r.categoria = ? ORDER BY r.tempo_totale, r.impostato_il LIMIT 1`, t.ID, category)
	}
	e, err := scanEntry(row, t, meID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if e != nil {
		e.Rank = 1
	}
	return e, err
}

/* ------------------------------ Giri ---------------------------------- */

type LapResult struct {
	Entry    *Entry `json:"entry"`
	Improved bool   `json:"improved"` // vero se è il nuovo record personale
}

// PROCEDURA: invio di un giro. Salva solo se migliora il record personale
// (stesso tracciato e categoria); in ogni caso aggiorna i migliori settori.
// Idempotente: lo stesso giro inviato due volte restituisce lo stesso lapId.
func SubmitLap(tx *sql.Tx, t *TrackRow, pilotID int64, category string, vehicle *string, timed []int64, setAt, now int64) (*LapResult, error) {
	var total int64
	for _, v := range timed {
		total += v
	}
	var curUID string
	var curTotal, curSet int64
	err := tx.QueryRow(`SELECT uid, tempo_totale, impostato_il FROM risultati
		WHERE tracciato_id = ? AND pilota_id = ? AND categoria = ?`, t.ID, pilotID, category).Scan(&curUID, &curTotal, &curSet)
	hasRecord := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	duplicate := hasRecord && curTotal == total && curSet == setAt
	improved := !hasRecord || total < curTotal
	want := curUID
	if improved {
		want = newUID()
		var s [10]any
		for i := range timed {
			s[i] = timed[i]
		}
		_, err = tx.Exec(`INSERT INTO risultati (uid, tracciato_id, pilota_id, categoria, veicolo,
				s1, s2, s3, s4, s5, s6, s7, s8, s9, s10, impostato_il, ricevuto_il)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (tracciato_id, pilota_id, categoria) DO UPDATE SET
				uid = excluded.uid, veicolo = excluded.veicolo,
				s1 = excluded.s1, s2 = excluded.s2, s3 = excluded.s3, s4 = excluded.s4, s5 = excluded.s5,
				s6 = excluded.s6, s7 = excluded.s7, s8 = excluded.s8, s9 = excluded.s9, s10 = excluded.s10,
				impostato_il = excluded.impostato_il, ricevuto_il = excluded.ricevuto_il`,
			want, t.ID, pilotID, category, vehicle,
			s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], setAt, now)
		if err != nil {
			return nil, err
		}
	}
	if !t.OverTen && !duplicate {
		var s [10]any
		for i := range timed {
			s[i] = timed[i]
		}
		// min() di SQLite con un NULL dà NULL: coalesce sceglie il valore presente.
		if _, err := tx.Exec(`INSERT INTO migliori_settori (tracciato_id, pilota_id, categoria, s1, s2, s3, s4, s5, s6, s7, s8, s9, s10)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT DO UPDATE SET
				s1 = coalesce(min(s1, excluded.s1), s1, excluded.s1), s2 = coalesce(min(s2, excluded.s2), s2, excluded.s2),
				s3 = coalesce(min(s3, excluded.s3), s3, excluded.s3), s4 = coalesce(min(s4, excluded.s4), s4, excluded.s4),
				s5 = coalesce(min(s5, excluded.s5), s5, excluded.s5), s6 = coalesce(min(s6, excluded.s6), s6, excluded.s6),
				s7 = coalesce(min(s7, excluded.s7), s7, excluded.s7), s8 = coalesce(min(s8, excluded.s8), s8, excluded.s8),
				s9 = coalesce(min(s9, excluded.s9), s9, excluded.s9), s10 = coalesce(min(s10, excluded.s10), s10, excluded.s10)`,
			t.ID, pilotID, category, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9]); err != nil {
			return nil, err
		}
	}
	if improved || duplicate {
		// Posizione nella classifica della categoria del giro.
		board, err := Leaderboard(tx, t, &category, pilotID, 0)
		if err != nil {
			return nil, err
		}
		for _, e := range board {
			if e.LapID != nil && *e.LapID == want {
				return &LapResult{Entry: e, Improved: improved}, nil
			}
		}
	}
	// Giro più lento del record: non salvato. Si restituisce la posizione
	// che avrebbe (rank 0 come nel server simulato, nessun lapId).
	e := &Entry{Rank: 0, LapMs: total, SetAt: setAt, IsMe: true, Vehicle: vehicle,
		SectorsMs: ExpandSectors(t.TotalSectors, t.NeutralMask, t.OverTen, timed)}
	if category != "" {
		e.VehicleType = &category
	}
	tx.QueryRow(`SELECT nickname FROM piloti WHERE id = ?`, pilotID).Scan(&e.Driver)
	return &LapResult{Entry: e}, nil
}

/* ------------------------------ Comparativa --------------------------- */

type Neighbor struct {
	Entry *Entry `json:"entry"`
	GapMs int64  `json:"gapMs"` // mio tempo - suo tempo (negativo = sono più veloce)
}

type SectorCompare struct {
	Index           int     `json:"index"`  // posizione nel giro (come sectorsMs dell'app)
	Number          int     `json:"number"` // numero del settore cronometrato (da 1)
	Name            string  `json:"name"`
	CommunityBestMs *int64  `json:"communityBestMs"`
	CommunityDriver *string `json:"communityBestDriver"`
	MyBestMs        *int64  `json:"myBestMs"` // miglior settore personale di sempre
	MyLapMs         *int64  `json:"myLapMs"`  // settore nel giro record
	GapMs           *int64  `json:"gapMs"`    // myBest - communityBest
	Purple          bool    `json:"purple"`   // il record di settore è mio
}

type Comparison struct {
	TrackID       string          `json:"trackId"`
	VehicleType   *string         `json:"vehicleType"`
	Drivers       int             `json:"drivers"`
	Me            *Entry          `json:"me"`
	Best          *Entry          `json:"best"`
	GapToBestMs   *int64          `json:"gapToBestMs"`
	Ahead         *Neighbor       `json:"ahead"`         // chi mi precede subito
	Behind        *Neighbor       `json:"behind"`        // chi mi segue subito
	FasterThanPct *float64        `json:"fasterThanPct"` // % di piloti più lenti di me
	SectorsHidden bool            `json:"sectorsHidden"`
	Sectors       []SectorCompare `json:"sectors"`
	IdealLapMs    *int64          `json:"idealLapMs"`   // somma dei record di settore della community
	MyIdealLapMs  *int64          `json:"myIdealLapMs"` // somma dei miei migliori settori
}

// PROCEDURA: confronto con la community e con i propri record.
func Compare(db querier, t *TrackRow, category string, meID int64) (*Comparison, error) {
	var filter *string
	if category != "" {
		filter = &category
	}
	board, err := Leaderboard(db, t, filter, meID, 0)
	if err != nil {
		return nil, err
	}
	c := &Comparison{TrackID: t.UID, Drivers: len(board), SectorsHidden: t.OverTen, Sectors: []SectorCompare{}}
	if category != "" {
		c.VehicleType = &category
	}
	if len(board) > 0 {
		c.Best = board[0]
	}
	for i, e := range board {
		if !e.IsMe {
			continue
		}
		c.Me = e
		g := e.LapMs - board[0].LapMs
		c.GapToBestMs = &g
		if i > 0 {
			c.Ahead = &Neighbor{board[i-1], e.LapMs - board[i-1].LapMs}
		}
		if i+1 < len(board) {
			c.Behind = &Neighbor{board[i+1], e.LapMs - board[i+1].LapMs}
		}
		if len(board) > 1 {
			p := math.Round(float64(len(board)-1-i)/float64(len(board)-1)*1000) / 10
			c.FasterThanPct = &p
		}
		break
	}
	if t.OverTen {
		return c, nil
	}

	// Migliori settori: una riga per pilota (e categoria) del tracciato.
	q := `SELECT m.pilota_id, p.nickname, p.visibilita, m.s1, m.s2, m.s3, m.s4, m.s5, m.s6, m.s7, m.s8, m.s9, m.s10
		FROM migliori_settori m JOIN piloti p ON p.id = m.pilota_id WHERE m.tracciato_id = ?`
	args := []any{t.ID}
	if category != "" {
		q += ` AND m.categoria = ?`
		args = append(args, category)
	}
	rows, err := db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	n := t.TimedSectors
	best := make([]int64, n)
	who := make([]string, n)
	whoMe := make([]bool, n)
	mine := make([]int64, n)
	for rows.Next() {
		var pid int64
		var nick, vis string
		var s [10]sql.NullInt64
		if err := rows.Scan(&pid, &nick, &vis, &s[0], &s[1], &s[2], &s[3], &s[4], &s[5], &s[6], &s[7], &s[8], &s[9]); err != nil {
			return nil, err
		}
		if vis == "PRIVATE" && pid != meID {
			nick = privateDriver
		}
		for k := 0; k < n; k++ {
			if !s[k].Valid {
				continue
			}
			v := s[k].Int64
			if best[k] == 0 || v < best[k] {
				best[k], who[k], whoMe[k] = v, nick, pid == meID
			}
			if pid == meID && (mine[k] == 0 || v < mine[k]) {
				mine[k] = v
			}
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	var ideal, myIdeal int64
	idealOK, myOK := true, true
	timedIdx := 0
	for k := 0; k < t.TotalSectors; k++ {
		if t.NeutralMask&(1<<uint(k)) != 0 {
			continue
		}
		sc := SectorCompare{Index: k, Number: timedIdx + 1}
		if timedIdx < len(t.SectorNames) {
			sc.Name = t.SectorNames[timedIdx]
		}
		if b := best[timedIdx]; b > 0 {
			sc.CommunityBestMs, sc.CommunityDriver = ptr(b), ptr(who[timedIdx])
			sc.Purple = whoMe[timedIdx]
			ideal += b
		} else {
			idealOK = false
		}
		if m := mine[timedIdx]; m > 0 {
			sc.MyBestMs = ptr(m)
			myIdeal += m
			if sc.CommunityBestMs != nil {
				sc.GapMs = ptr(m - *sc.CommunityBestMs)
			}
		} else {
			myOK = false
		}
		if c.Me != nil && timedIdx < len(c.Me.timed) {
			sc.MyLapMs = ptr(c.Me.timed[timedIdx])
		}
		c.Sectors = append(c.Sectors, sc)
		timedIdx++
	}
	if idealOK && n > 0 {
		c.IdealLapMs = &ideal
	}
	if myOK && n > 0 {
		c.MyIdealLapMs = &myIdeal
	}
	return c, nil
}

/* ------------------------------ Record personali ---------------------- */

type PersonalRecord struct {
	TrackID       string  `json:"trackId"`
	TrackName     string  `json:"trackName"`
	VehicleType   *string `json:"vehicleType"`
	LapMs         int64   `json:"lapMs"`
	SetAt         int64   `json:"setAt"`
	LapID         string  `json:"lapId"`
	Rank          int     `json:"rank"`    // posizione nella categoria
	Drivers       int     `json:"drivers"` // piloti nella categoria
	GapToBestMs   int64   `json:"gapToBestMs"`
	TrackRecordMs *int64  `json:"trackRecordMs"` // record assoluto del tracciato
}

// PROCEDURA: record personali di un pilota su tutti i tracciati, con
// posizione e distacco. Ogni dato è un conteggio o un minimo sull'indice
// (tracciato, categoria, tempo): costo logaritmico, niente scansioni.
func PersonalRecords(db querier, pilotID int64) ([]PersonalRecord, error) {
	rows, err := db.Query(`SELECT r.tracciato_id, t.uid, t.nome, r.categoria, r.tempo_totale, r.impostato_il, r.uid,
			(SELECT b.tempo_totale FROM risultati b WHERE b.id = t.miglior_risultato_id)
		FROM risultati r JOIN tracciati t ON t.id = r.tracciato_id
		WHERE r.pilota_id = ? ORDER BY r.impostato_il DESC`, pilotID)
	if err != nil {
		return nil, err
	}
	type rec struct {
		trackID int64
		cat     string
		PersonalRecord
	}
	var list []rec
	for rows.Next() {
		var r rec
		var rec sql.NullInt64
		if err := rows.Scan(&r.trackID, &r.TrackID, &r.TrackName, &r.cat, &r.LapMs, &r.SetAt, &r.LapID, &rec); err != nil {
			rows.Close()
			return nil, err
		}
		if r.cat != "" {
			c := r.cat
			r.VehicleType = &c
		}
		if rec.Valid {
			r.TrackRecordMs = &rec.Int64
		}
		list = append(list, r)
	}
	rows.Close()
	out := make([]PersonalRecord, 0, len(list))
	for _, r := range list {
		var ahead, best int64
		if err := db.QueryRow(`SELECT count(*), min(tempo_totale) FROM risultati
			WHERE tracciato_id = ? AND categoria = ? AND (tempo_totale < ? OR (tempo_totale = ? AND impostato_il < ?))`,
			r.trackID, r.cat, r.LapMs, r.LapMs, r.SetAt).Scan(&ahead, new(sql.NullInt64)); err != nil {
			return nil, err
		}
		if err := db.QueryRow(`SELECT count(*), min(tempo_totale) FROM risultati WHERE tracciato_id = ? AND categoria = ?`,
			r.trackID, r.cat).Scan(&r.Drivers, &best); err != nil {
			return nil, err
		}
		r.Rank = int(ahead) + 1
		r.GapToBestMs = r.LapMs - best
		out = append(out, r.PersonalRecord)
	}
	return out, nil
}

/* ------------------------------ Tracciato pubblicato ------------------ */

// PublishedTrackJSON: oggetto PublishedTrack del contratto (geometria
// completa, autore, piloti, prime 3 posizioni).
func PublishedTrackJSON(db querier, t *TrackRow, meID int64, distance *float64) (map[string]any, error) {
	var raw string
	if err := db.QueryRow(`SELECT json FROM tracciati_geometria WHERE tracciato_id = ?`, t.ID).Scan(&raw); err != nil {
		return nil, err
	}
	geom := json.RawMessage(raw)
	drivers, err := DriverCount(db, t.ID)
	if err != nil {
		return nil, err
	}
	top, err := Leaderboard(db, t, nil, meID, 3)
	if err != nil {
		return nil, err
	}
	author := t.Author
	if t.AuthorVisibility == "PRIVATE" && t.AuthorID != meID {
		author = privateDriver
	}
	return map[string]any{
		"id":            t.UID,
		"author":        author,
		"publishedAt":   t.PublishedAt,
		"updatedAt":     t.UpdatedAt,
		"drivers":       drivers,
		"distanceM":     distance,
		"track":         geom,
		"topTimes":      top,
		"sectorNames":   t.SectorNames,
		"timedSectors":  t.TimedSectors,
		"sectorsHidden": t.OverTen,
	}, nil
}

// Nearby: tracciati con la partenza entro radiusKm, dal più vicino.
// Il riquadro lat/lon usa l'indice tracciati_posizione; la distanza esatta
// si calcola solo sui candidati.
func Nearby(db querier, lat, lon, radiusKm float64, limit int) ([]*TrackRow, []float64, error) {
	dLat := radiusKm / 111.32
	cos := math.Cos(lat * math.Pi / 180)
	dLon := 180.0
	if cos > 0.01 {
		dLon = math.Min(180, radiusKm/(111.32*cos))
	}
	cands, err := queryTracks(db, `WHERE t.lat BETWEEN ? AND ? AND t.lon BETWEEN ? AND ?`, lat-dLat, lat+dLat, lon-dLon, lon+dLon)
	if err != nil {
		return nil, nil, err
	}
	type pair struct {
		t *TrackRow
		d float64
	}
	var ps []pair
	for _, t := range cands {
		if d := distanceM(lat, lon, t.Lat, t.Lon); d <= radiusKm*1000 {
			ps = append(ps, pair{t, d})
		}
	}
	sort.Slice(ps, func(i, j int) bool { return ps[i].d < ps[j].d })
	if len(ps) > limit {
		ps = ps[:limit]
	}
	ts := make([]*TrackRow, len(ps))
	ds := make([]float64, len(ps))
	for i, p := range ps {
		ts[i], ds[i] = p.t, math.Round(p.d*10)/10
	}
	return ts, ds, nil
}

func likePattern(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return "%" + r.Replace(strings.TrimSpace(s)) + "%"
}

func ptr[T any](v T) *T { return &v }

/* ------------------------------ Amici --------------------------------- */

// MaxFriends: amici per richiesta (la lista vive sul telefono).
const MaxFriends = 200

type Driver struct {
	UserID      string  `json:"userId"`
	Nickname    string  `json:"nickname"`
	DisplayName *string `json:"displayName"`
	AvatarURL   *string `json:"avatarUrl"`
}

// PROCEDURA: profilo pubblico di un pilota dal nickname (per aggiungerlo
// agli amici). nil se non esiste o ha il profilo privato (salvo sé stessi).
func FindDriver(db querier, nickname string, meID int64) (*Driver, error) {
	var d Driver
	var id int64
	var vis string
	var name, avatar sql.NullString
	err := db.QueryRow(`SELECT id, uid, nickname, nome, foto_url, visibilita FROM piloti WHERE nickname = ? COLLATE NOCASE`,
		strings.TrimSpace(nickname)).Scan(&id, &d.UserID, &d.Nickname, &name, &avatar, &vis)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if vis == "PRIVATE" && id != meID {
		return nil, nil
	}
	if name.Valid {
		d.DisplayName = &name.String
	}
	if avatar.Valid {
		d.AvatarURL = &avatar.String
	}
	return &d, nil
}

type FriendTime struct {
	UserID   string  `json:"userId"`
	Nickname *string `json:"nickname"` // nome attuale (aggiorna la lista locale se è cambiato)
	Found    bool    `json:"found"`    // falso: account eliminato o profilo privato
	Entry    *Entry  `json:"entry"`    // nil: nessun tempo su questo tracciato
	GapMs    *int64  `json:"gapMs"`    // suo tempo - mio tempo (negativo = è più veloce)
}

type FriendsTimes struct {
	TrackID     string       `json:"trackId"`
	VehicleType *string      `json:"vehicleType"`
	Drivers     int          `json:"drivers"` // piloti in classifica (per leggere la posizione)
	Me          *Entry       `json:"me"`
	Friends     []FriendTime `json:"friends"` // con tempo dal più veloce, poi senza tempo
}

// PROCEDURA: tempi degli amici su un tracciato. La posizione (rank) è quella
// nella classifica completa; i profili privati non vengono mostrati.
func FriendTimes(db querier, t *TrackRow, category string, meID int64, userIDs []string) (*FriendsTimes, error) {
	var filter *string
	res := &FriendsTimes{TrackID: t.UID, Friends: []FriendTime{}}
	if category != "" {
		filter = &category
		res.VehicleType = &category
	}
	// Elenco senza doppioni.
	seen := map[string]bool{}
	ids := []any{}
	for _, u := range userIDs {
		u = strings.TrimSpace(u)
		if u != "" && !seen[u] && len(ids) < MaxFriends {
			seen[u] = true
			ids = append(ids, u)
		}
	}
	type pilot struct {
		id   int64
		nick string
		vis  string
	}
	pilots := map[string]pilot{}
	if len(ids) > 0 {
		rows, err := db.Query(`SELECT id, uid, nickname, visibilita FROM piloti WHERE uid IN (?`+strings.Repeat(",?", len(ids)-1)+`)`, ids...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var p pilot
			var uid string
			if err := rows.Scan(&p.id, &uid, &p.nick, &p.vis); err != nil {
				rows.Close()
				return nil, err
			}
			pilots[uid] = p
		}
		rows.Close()
	}
	board, err := Leaderboard(db, t, filter, meID, 0)
	if err != nil {
		return nil, err
	}
	res.Drivers = len(board)
	byPilot := map[int64]*Entry{}
	for _, e := range board {
		byPilot[e.pilotID] = e
		if e.IsMe {
			res.Me = e
		}
	}
	var timed, untimed []FriendTime
	for _, raw := range ids {
		uid := raw.(string)
		f := FriendTime{UserID: uid}
		p, ok := pilots[uid]
		if !ok || (p.vis == "PRIVATE" && p.id != meID) {
			untimed = append(untimed, f)
			continue
		}
		f.Found = true
		f.Nickname = ptr(p.nick)
		if e := byPilot[p.id]; e != nil {
			f.Entry = e
			if res.Me != nil {
				f.GapMs = ptr(e.LapMs - res.Me.LapMs)
			}
			timed = append(timed, f)
		} else {
			untimed = append(untimed, f)
		}
	}
	sort.Slice(timed, func(i, j int) bool { return timed[i].Entry.Rank < timed[j].Entry.Rank })
	sort.SliceStable(untimed, func(i, j int) bool {
		if untimed[i].Found != untimed[j].Found {
			return untimed[i].Found
		}
		return strings.ToLower(deref(untimed[i].Nickname)) < strings.ToLower(deref(untimed[j].Nickname))
	})
	res.Friends = append(append(res.Friends, timed...), untimed...)
	return res, nil
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
