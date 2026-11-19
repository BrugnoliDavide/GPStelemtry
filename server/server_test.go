package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

/* ------------------------------ Supporto ------------------------------ */

type env struct {
	t   *testing.T
	srv *httptest.Server
	app *App
}

func newEnv(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	store, err := OpenStore(filepath.Join(dir, "test.db"), 2, 5*time.Millisecond, 256)
	if err != nil {
		t.Fatal(err)
	}
	clock := int64(1_800_000_000_000)
	var mu sync.Mutex
	app := &App{db: store, avatarsDir: dir, publicURL: "https://gps.example", loginRL: NewRateLimit(100, 100),
		now: func() int64 { mu.Lock(); defer mu.Unlock(); clock++; return clock }}
	srv := httptest.NewServer(NewLimiter(8, time.Second).Wrap(app.Routes()))
	t.Cleanup(func() { srv.Close(); store.Close() })
	return &env{t, srv, app}
}

type resp struct {
	status int
	body   []byte
	header http.Header
}

func (r resp) obj() map[string]any {
	var m map[string]any
	json.Unmarshal(r.body, &m)
	return m
}

func (r resp) arr() []map[string]any {
	var a []map[string]any
	json.Unmarshal(r.body, &a)
	return a
}

func (e *env) do(method, path, token string, body any) resp {
	e.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case []byte:
		rd = bytes.NewReader(b)
	default:
		j, _ := json.Marshal(b)
		rd = bytes.NewReader(j)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return resp{res.StatusCode, b, res.Header}
}

func (e *env) expect(r resp, status int) resp {
	e.t.Helper()
	if r.status != status {
		e.t.Fatalf("stato %d invece di %d: %s", r.status, status, r.body)
	}
	return r
}

// user registra un pilota e restituisce il token.
func (e *env) user(nick string) string {
	e.t.Helper()
	r := e.expect(e.do("POST", "/v1/users", "", map[string]any{"nickname": nick}), 201)
	return r.obj()["token"].(string)
}

type sp struct {
	name    string
	neutral bool
}

// trackJSON: tracciato circolare con i parziali indicati (formato Json.track dell'app).
func trackJSON(name string, splits ...sp) map[string]any {
	ss := []any{}
	for i, s := range splits {
		m := map[string]any{"lat": 45.0 + float64(i+1)*0.001, "lon": 9.0, "heading": 0, "width": 20, "name": s.name}
		if s.neutral {
			m["neutral"] = true
		}
		ss = append(ss, m)
	}
	return map[string]any{
		"id": "local-" + name, "name": name, "type": "CIRCULAR", "tolerance": 15, "createdAt": 1,
		"start":  map[string]any{"lat": 45.0, "lon": 9.0, "heading": 90, "width": 30},
		"finish": nil, "splits": ss, "path": []any{map[string]any{"lat": 45.0, "lon": 9.0}},
	}
}

func (e *env) publish(token string, track map[string]any) map[string]any {
	e.t.Helper()
	return e.expect(e.do("POST", "/v1/tracks", token, map[string]any{"track": track}), 201).obj()
}

func lap(lapMs int64, setAt int64, vt any, sectors ...int64) map[string]any {
	return map[string]any{"lapMs": lapMs, "sectorsMs": sectors, "setAt": setAt, "vehicleType": vt, "vehicle": nil}
}

func sum(xs ...int64) int64 {
	var s int64
	for _, x := range xs {
		s += x
	}
	return s
}

func (e *env) send(token, trackID string, setAt int64, vt any, sectors ...int64) map[string]any {
	e.t.Helper()
	return e.expect(e.do("POST", "/v1/tracks/"+trackID+"/laps", token, lap(sum(sectors...), setAt, vt, sectors...)), 201).obj()
}

func num(v any) int64 { return int64(v.(float64)) }

func nums(v any) []int64 {
	out := []int64{}
	for _, x := range v.([]any) {
		out = append(out, num(x))
	}
	return out
}

func eq(a, b []int64) bool { return fmt.Sprint(a) == fmt.Sprint(b) }

/* ------------------------------ Nickname ------------------------------ */

func TestNickname(t *testing.T) {
	e := newEnv(t)
	r := e.expect(e.do("GET", "/v1/nicknames/Marta/availability", "", nil), 200).obj()
	if r["available"] != true {
		t.Fatalf("Marta dovrebbe essere libero: %v", r)
	}
	tok := e.user("Marta")
	r = e.do("GET", "/v1/nicknames/marta/availability", "", nil).obj()
	if r["available"] != false || r["reason"] != "Già in uso" || len(r["suggestions"].([]any)) == 0 {
		t.Fatalf("marta (maiuscole indifferenti) deve risultare in uso, con suggerimenti: %v", r)
	}
	// Per chi lo possiede il proprio nome è disponibile.
	if r := e.do("GET", "/v1/nicknames/MARTA/availability", tok, nil).obj(); r["available"] != true {
		t.Fatalf("il proprio nickname è libero per sé: %v", r)
	}
	if r := e.do("GET", "/v1/nicknames/Admin/availability", "", nil).obj(); r["reason"] != "Nickname riservato" {
		t.Fatalf("riservato: %v", r)
	}
	if r := e.do("GET", "/v1/nicknames/.ab/availability", "", nil).obj(); r["available"] != false {
		t.Fatalf("formato non valido: %v", r)
	}
	e.expect(e.do("POST", "/v1/users", "", map[string]any{"nickname": "MARTA"}), 409)
	e.expect(e.do("POST", "/v1/users", "", map[string]any{"nickname": "a b"}), 400)
	// Rinomina e il vecchio nome torna libero.
	e.expect(e.do("PATCH", "/v1/users/me", tok, map[string]any{"nickname": "marta.gp"}), 200)
	if r := e.do("GET", "/v1/nicknames/Marta/availability", "", nil).obj(); r["available"] != true {
		t.Fatalf("dopo la rinomina Marta è libero: %v", r)
	}
}

func TestRegistrazioniSimultaneeStessoNome(t *testing.T) {
	e := newEnv(t)
	var wg sync.WaitGroup
	codes := make(chan int, 10)
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes <- e.do("POST", "/v1/users", "", map[string]any{"nickname": "gemello"}).status
		}()
	}
	wg.Wait()
	close(codes)
	ok := 0
	for c := range codes {
		if c == 201 {
			ok++
		} else if c != 409 {
			t.Fatalf("stato inatteso %d", c)
		}
	}
	if ok != 1 {
		t.Fatalf("un solo account deve ottenere il nome, invece %d", ok)
	}
}

func TestPassword(t *testing.T) {
	e := newEnv(t)
	e.expect(e.do("POST", "/v1/users", "", map[string]any{"nickname": "luca", "password": "corta"}), 400)
	r := e.expect(e.do("POST", "/v1/users", "", map[string]any{
		"nickname": "luca", "email": "Luca@Example.com", "password": "segreta123", "displayName": "Luca R."}), 201).obj()
	tok := r["token"].(string)
	if r["hasPassword"] != true || r["displayName"] != "Luca R." {
		t.Fatalf("account: %v", r)
	}
	e.expect(e.do("POST", "/v1/users", "", map[string]any{"nickname": "luca2", "email": "luca@example.com"}), 409)
	e.expect(e.do("POST", "/v1/sessions", "", map[string]any{"login": "LUCA", "password": "segreta123"}), 200)
	e.expect(e.do("POST", "/v1/sessions", "", map[string]any{"login": "luca@example.com", "password": "segreta123"}), 200)
	e.expect(e.do("POST", "/v1/sessions", "", map[string]any{"login": "luca", "password": "sbagliata1"}), 401)
	e.expect(e.do("POST", "/v1/sessions", "", map[string]any{"login": "nessuno", "password": "segreta123"}), 401)
	// Cambio password: serve quella attuale; gli altri accessi vengono chiusi.
	other := e.expect(e.do("POST", "/v1/sessions", "", map[string]any{"login": "luca", "password": "segreta123"}), 200).obj()["token"].(string)
	e.expect(e.do("PATCH", "/v1/users/me", tok, map[string]any{"password": "nuovissima1"}), 403)
	e.expect(e.do("PATCH", "/v1/users/me", tok, map[string]any{"password": "nuovissima1", "currentPassword": "segreta123"}), 200)
	e.expect(e.do("GET", "/v1/users/me", other, nil), 401)
	e.expect(e.do("GET", "/v1/users/me", tok, nil), 200)
	e.expect(e.do("POST", "/v1/sessions", "", map[string]any{"login": "luca", "password": "nuovissima1"}), 200)
	// Logout.
	e.expect(e.do("DELETE", "/v1/sessions/current", tok, nil), 204)
	e.expect(e.do("GET", "/v1/users/me", tok, nil), 401)
}

/* --------------------------- Neutralizzazioni ------------------------- */

func TestNeutralizzazioniNonContano(t *testing.T) {
	e := newEnv(t)
	tok := e.user("anna")
	// Partenza → P1 → [N ingresso] → uscita → traguardo: 4 settori, il 3° neutro.
	pt := e.publish(tok, trackJSON("Lago", sp{"Curva 1", false}, sp{"Neutralizzazione 1", true}, sp{"Fine neutralizzazione 1", false}))
	id := pt["id"].(string)
	if num(pt["timedSectors"]) != 3 || fmt.Sprint(pt["sectorNames"]) != "[Curva 1 Settore 2 Settore 3]" {
		t.Fatalf("settori cronometrati: %v %v", pt["timedSectors"], pt["sectorNames"])
	}
	tr := pt["track"].(map[string]any)
	if tr["remoteId"] != id || tr["published"] != true {
		t.Fatalf("geometria restituita: %v", tr)
	}
	// Il settore neutro deve valere 0.
	e.expect(e.do("POST", "/v1/tracks/"+id+"/laps", tok, lap(40000, 1, nil, 10000, 12000, 7000, 11000)), 400)
	// Somma diversa dal giro.
	e.expect(e.do("POST", "/v1/tracks/"+id+"/laps", tok, lap(99999, 1, nil, 10000, 12000, 0, 11000)), 400)
	// Numero di settori sbagliato.
	e.expect(e.do("POST", "/v1/tracks/"+id+"/laps", tok, lap(22000, 1, nil, 10000, 12000)), 400)
	// Giro valido.
	r := e.send(tok, id, 100, nil, 10000, 12000, 0, 11000)
	if num(r["lapMs"]) != 33000 || num(r["rank"]) != 1 || r["improved"] != true || !eq(nums(r["sectorsMs"]), []int64{10000, 12000, 0, 11000}) {
		t.Fatalf("giro: %v", r)
	}
	// Nel database: tre settori cronometrati, totale calcolato.
	var s1, s2, s3 int64
	var s4 sql.NullInt64
	var total int64
	e.app.db.read.QueryRow(`SELECT s1, s2, s3, s4, tempo_totale FROM risultati`).Scan(&s1, &s2, &s3, &s4, &total)
	if s1 != 10000 || s2 != 12000 || s3 != 11000 || s4.Valid || total != 33000 {
		t.Fatalf("righe salvate: %d %d %d %v %d", s1, s2, s3, s4, total)
	}
}

/* ------------------------------ Solo record --------------------------- */

func TestSoloIlRecordVieneSalvato(t *testing.T) {
	e := newEnv(t)
	tok := e.user("anna")
	id := e.publish(tok, trackJSON("Pista", sp{"P1", false}))["id"].(string)

	first := e.send(tok, id, 100, nil, 20000, 21000) // 41000
	lapID := first["lapId"].(string)

	// Più lento ma con il primo settore migliore: non sostituisce il record.
	slow := e.send(tok, id, 200, nil, 19000, 23000) // 42000
	if slow["improved"] != false || num(slow["rank"]) != 0 || slow["lapId"] != nil {
		t.Fatalf("giro più lento: %v", slow)
	}
	// Stesso giro reinviato: stesso lapId (idempotente).
	dup := e.send(tok, id, 100, nil, 20000, 21000)
	if dup["lapId"] != lapID || dup["improved"] != false || num(dup["rank"]) != 1 {
		t.Fatalf("doppio invio: %v", dup)
	}
	board := e.expect(e.do("GET", "/v1/tracks/"+id+"/leaderboard", "", nil), 200).arr()
	if len(board) != 1 || num(board[0]["lapMs"]) != 41000 {
		t.Fatalf("classifica: %v", board)
	}
	var n int
	e.app.db.read.QueryRow(`SELECT count(*) FROM risultati`).Scan(&n)
	if n != 1 {
		t.Fatalf("righe risultati: %d", n)
	}
	// Migliori settori personali combinati da giri diversi: 19000 + 21000.
	c := e.expect(e.do("GET", "/v1/tracks/"+id+"/compare", tok, nil), 200).obj()
	if num(c["myIdealLapMs"]) != 40000 {
		t.Fatalf("giro ideale personale: %v", c["myIdealLapMs"])
	}
	// Più veloce: nuovo record, nuovo lapId.
	fast := e.send(tok, id, 300, nil, 19500, 20000)
	if fast["improved"] != true || fast["lapId"] == lapID || num(fast["lapMs"]) != 39500 {
		t.Fatalf("nuovo record: %v", fast)
	}
	// Il vecchio lapId non esiste più.
	e.expect(e.do("DELETE", "/v1/laps/"+lapID, tok, nil), 404)
}

/* ------------------------------ Comparativa --------------------------- */

func TestComparativaCommunity(t *testing.T) {
	e := newEnv(t)
	anna, bruno, carla, dario := e.user("anna"), e.user("bruno"), e.user("carla"), e.user("dario")
	id := e.publish(anna, trackJSON("Pista", sp{"Esse", false}, sp{"N", true}, sp{"Uscita", false}))["id"].(string)
	// Settori: S1, S2, [neutro], S3.
	e.send(anna, id, 1, nil, 10000, 10000, 0, 10000)  // 30000
	e.send(bruno, id, 2, nil, 9000, 11500, 0, 10500)  // 31000 – primo settore migliore di tutti
	e.send(carla, id, 3, nil, 11000, 11000, 0, 10000) // 32000
	e.send(dario, id, 4, nil, 12000, 11000, 0, 10000) // 33000
	// Carla diventa privata: gli altri la vedono come "Pilota privato".
	e.expect(e.do("PATCH", "/v1/users/me", carla, map[string]any{"visibility": "PRIVATE"}), 200)

	c := e.expect(e.do("GET", "/v1/tracks/"+id+"/compare", bruno, nil), 200).obj()
	me := c["me"].(map[string]any)
	if num(me["rank"]) != 2 || num(c["drivers"]) != 4 || num(c["gapToBestMs"]) != 1000 {
		t.Fatalf("posizione: %v", c)
	}
	ahead, behind := c["ahead"].(map[string]any), c["behind"].(map[string]any)
	if ahead["entry"].(map[string]any)["driver"] != "anna" || num(ahead["gapMs"]) != 1000 {
		t.Fatalf("davanti: %v", ahead)
	}
	if behind["entry"].(map[string]any)["driver"] != privateDriver || num(behind["gapMs"]) != -1000 {
		t.Fatalf("dietro: %v", behind)
	}
	if c["fasterThanPct"].(float64) != 66.7 {
		t.Fatalf("percentuale: %v", c["fasterThanPct"])
	}
	secs := c["sectors"].([]any)
	if len(secs) != 3 {
		t.Fatalf("settori cronometrati: %v", secs)
	}
	s0, s2 := secs[0].(map[string]any), secs[2].(map[string]any)
	if s0["purple"] != true || num(s0["communityBestMs"]) != 9000 || s0["name"] != "Esse" || num(s0["gapMs"]) != 0 {
		t.Fatalf("settore 1: %v", s0)
	}
	// Terzo settore cronometrato = indice 3 nel giro (dopo la neutralizzazione).
	if num(s2["index"]) != 3 || num(s2["number"]) != 3 || num(s2["communityBestMs"]) != 10000 || num(s2["gapMs"]) != 500 || s2["purple"] != false {
		t.Fatalf("settore 3: %v", s2)
	}
	if num(c["idealLapMs"]) != 29000 {
		t.Fatalf("giro ideale community: %v", c["idealLapMs"])
	}
	// Carla vede sé stessa con il proprio nome.
	board := e.do("GET", "/v1/tracks/"+id+"/leaderboard", carla, nil).arr()
	if board[2]["driver"] != "carla" || board[2]["isMe"] != true {
		t.Fatalf("carla vede sé stessa: %v", board[2])
	}
	// Tempo migliore del tracciato.
	b := e.expect(e.do("GET", "/v1/tracks/"+id+"/best", "", nil), 200).obj()["best"].(map[string]any)
	if b["driver"] != "anna" || num(b["lapMs"]) != 30000 {
		t.Fatalf("tempo migliore: %v", b)
	}
	// Record personali.
	recs := e.expect(e.do("GET", "/v1/users/me/records", dario, nil), 200).arr()
	if len(recs) != 1 || num(recs[0]["rank"]) != 4 || num(recs[0]["gapToBestMs"]) != 3000 || num(recs[0]["drivers"]) != 4 {
		t.Fatalf("record personali: %v", recs)
	}
}

func TestRecordDelTracciatoAggiornatoDaiTrigger(t *testing.T) {
	e := newEnv(t)
	anna, bruno := e.user("anna"), e.user("bruno")
	id := e.publish(anna, trackJSON("Pista"))["id"].(string)
	best := func() string {
		var nick sql.NullString
		e.app.db.read.QueryRow(`SELECT nickname FROM v_tempo_migliore WHERE tracciato_uid = ?`, id).Scan(&nick)
		return nick.String
	}
	if best() != "" {
		t.Fatal("senza giri non c'è record")
	}
	e.send(anna, id, 1, nil, 30000)
	bl := e.send(bruno, id, 2, nil, 29000)
	if best() != "bruno" {
		t.Fatalf("record: %q", best())
	}
	e.send(anna, id, 3, nil, 28000)
	if best() != "anna" {
		t.Fatalf("record: %q", best())
	}
	// Ritiro del giro di anna: il record torna a bruno? No: anna aveva 28000, ritirato → bruno 29000.
	var annaLap string
	e.app.db.read.QueryRow(`SELECT r.uid FROM risultati r JOIN piloti p ON p.id = r.pilota_id WHERE p.nickname = 'anna'`).Scan(&annaLap)
	e.expect(e.do("DELETE", "/v1/laps/"+annaLap, bruno, nil), 403) // non è suo
	e.expect(e.do("DELETE", "/v1/laps/"+annaLap, anna, nil), 204)
	if best() != "bruno" {
		t.Fatalf("dopo il ritiro: %q", best())
	}
	e.expect(e.do("DELETE", "/v1/laps/"+bl["lapId"].(string), bruno, nil), 204)
	if best() != "" {
		t.Fatalf("senza giri: %q", best())
	}
}

/* ----------------------------- Oltre 10 settori ----------------------- */

func TestOltreDieciSettori(t *testing.T) {
	e := newEnv(t)
	tok := e.user("anna")
	// 10 parziali di cui 1 ingresso neutro: 11 settori, 10 cronometrati → divisione visibile.
	ten := []sp{}
	for i := 0; i < 10; i++ {
		ten = append(ten, sp{fmt.Sprintf("P%d", i+1), i == 4})
	}
	p := e.publish(tok, trackJSON("Dieci", ten...))
	if p["sectorsHidden"] != false || num(p["timedSectors"]) != 10 {
		t.Fatalf("10 settori cronometrati: %v %v", p["sectorsHidden"], p["timedSectors"])
	}
	secs := []int64{}
	for k := 0; k < 11; k++ {
		if k == 5 {
			secs = append(secs, 0)
		} else {
			secs = append(secs, 1000+int64(k))
		}
	}
	r := e.send(tok, p["id"].(string), 1, nil, secs...)
	if len(r["sectorsMs"].([]any)) != 11 {
		t.Fatalf("settori restituiti: %v", r["sectorsMs"])
	}

	// 12 parziali senza neutri: 13 settori cronometrati → solo il totale.
	many := []sp{}
	for i := 0; i < 12; i++ {
		many = append(many, sp{fmt.Sprintf("P%d", i+1), false})
	}
	p = e.publish(tok, trackJSON("Tredici", many...))
	id := p["id"].(string)
	if p["sectorsHidden"] != true {
		t.Fatalf("oltre 10: %v", p["sectorsHidden"])
	}
	secs = []int64{}
	for k := 0; k < 13; k++ {
		secs = append(secs, 2000)
	}
	r = e.send(tok, id, 1, nil, secs...)
	if num(r["lapMs"]) != 26000 || len(r["sectorsMs"].([]any)) != 0 {
		t.Fatalf("giro oltre 10: %v", r)
	}
	var s1 int64
	var s2 sql.NullInt64
	var flag bool
	e.app.db.read.QueryRow(`SELECT r.s1, r.s2, t.oltre_10 FROM risultati r JOIN tracciati t ON t.id = r.tracciato_id WHERE t.uid = ?`, id).Scan(&s1, &s2, &flag)
	if s1 != 26000 || s2.Valid || !flag {
		t.Fatalf("salvataggio oltre 10: s1=%d s2=%v flag=%v", s1, s2, flag)
	}
	c := e.expect(e.do("GET", "/v1/tracks/"+id+"/compare", tok, nil), 200).obj()
	if c["sectorsHidden"] != true || len(c["sectors"].([]any)) != 0 {
		t.Fatalf("comparativa oltre 10: %v", c)
	}
}

/* ------------------------------ Categorie ----------------------------- */

func TestCategorieVeicolo(t *testing.T) {
	e := newEnv(t)
	anna, bruno := e.user("anna"), e.user("bruno")
	id := e.publish(anna, trackJSON("Pista"))["id"].(string)
	e.send(anna, id, 1, "KART", 50000)
	e.send(anna, id, 2, "CAR", 45000)
	e.send(bruno, id, 3, "KART", 48000)
	e.expect(e.do("POST", "/v1/tracks/"+id+"/laps", anna, lap(1000, 1, "ASTRONAVE", 1000)), 400)

	all := e.do("GET", "/v1/tracks/"+id+"/leaderboard", "", nil).arr()
	if len(all) != 2 || all[0]["driver"] != "anna" || all[0]["vehicleType"] != "CAR" || num(all[1]["rank"]) != 2 {
		t.Fatalf("classifica assoluta (un pilota una volta): %v", all)
	}
	kart := e.do("GET", "/v1/tracks/"+id+"/leaderboard?vehicleType=kart", "", nil).arr()
	if len(kart) != 2 || kart[0]["driver"] != "bruno" || num(kart[1]["lapMs"]) != 50000 {
		t.Fatalf("classifica kart: %v", kart)
	}
	b := e.do("GET", "/v1/tracks/"+id+"/best?vehicleType=KART", "", nil).obj()["best"].(map[string]any)
	if b["driver"] != "bruno" {
		t.Fatalf("migliore kart: %v", b)
	}
	if n := len(e.do("GET", "/v1/users/me/records", anna, nil).arr()); n != 2 {
		t.Fatalf("anna ha un record per categoria: %d", n)
	}
}

/* ----------------------------- Ripubblicazione ------------------------ */

func TestRipubblicazione(t *testing.T) {
	e := newEnv(t)
	anna, bruno := e.user("anna"), e.user("bruno")
	tr := trackJSON("Pista", sp{"P1", false})
	p := e.publish(anna, tr)
	id := p["id"].(string)
	e.send(anna, id, 1, nil, 10000, 10000)

	// Solo il nome cambia: stessa classifica.
	tr["remoteId"] = id
	tr["name"] = "Pista nuova"
	tr["splits"].([]any)[0].(map[string]any)["name"] = "Tornante"
	p = e.publish(anna, tr)
	if p["id"] != id || p["leaderboardReset"] != false || num(p["drivers"]) != 1 {
		t.Fatalf("rinomina: %v", p)
	}
	// Parziale spostato: classifica azzerata, stesso id.
	tr["splits"].([]any)[0].(map[string]any)["lat"] = 45.01
	p = e.publish(anna, tr)
	if p["id"] != id || p["leaderboardReset"] != true || num(p["drivers"]) != 0 {
		t.Fatalf("linee spostate: %v", p)
	}
	// Un altro utente con lo stesso remoteId crea un tracciato suo.
	p = e.publish(bruno, tr)
	if p["id"] == id {
		t.Fatal("bruno non può sovrascrivere il tracciato di anna")
	}
	e.expect(e.do("DELETE", "/v1/tracks/"+id, bruno, nil), 403)
	e.expect(e.do("DELETE", "/v1/tracks/"+id, anna, nil), 204)
	e.expect(e.do("GET", "/v1/tracks/"+id, "", nil), 404)
}

/* ------------------------------ Ricerca ------------------------------- */

func TestRicerca(t *testing.T) {
	e := newEnv(t)
	anna, bruno := e.user("anna_rc"), e.user("bruno")
	near := trackJSON("Pista del Lago")
	far := trackJSON("Monte Lontano")
	far["start"] = map[string]any{"lat": 46.0, "lon": 11.0, "heading": 0, "width": 30}
	idNear := e.publish(anna, near)["id"].(string)
	e.publish(anna, far)
	e.send(bruno, idNear, 1, nil, 30000)

	n := e.expect(e.do("GET", "/v1/tracks/nearby?lat=45.001&lon=9.0&radiusKm=20", "", nil), 200).arr()
	if len(n) != 1 || n[0]["id"] != idNear || n[0]["distanceM"].(float64) < 100 || n[0]["distanceM"].(float64) > 120 {
		t.Fatalf("vicini: %v", n)
	}
	if r := e.do("GET", "/v1/tracks/search?name=LAGO", "", nil).arr(); len(r) != 1 {
		t.Fatalf("per nome: %d", len(r))
	}
	if r := e.do("GET", "/v1/tracks/search?name=%25", "", nil).arr(); len(r) != 0 {
		t.Fatalf("il carattere %% è letterale: %d", len(r))
	}
	if r := e.do("GET", "/v1/tracks/search?driver=bru", "", nil).arr(); len(r) != 1 {
		t.Fatalf("per pilota (corsi): %d", len(r))
	}
	if r := e.do("GET", "/v1/tracks/search?driver=anna", "", nil).arr(); len(r) != 2 {
		t.Fatalf("per pilota (pubblicati): %d", len(r))
	}
	e.expect(e.do("PATCH", "/v1/users/me", bruno, map[string]any{"visibility": "PRIVATE"}), 200)
	if r := e.do("GET", "/v1/tracks/search?driver=bru", "", nil).arr(); len(r) != 0 {
		t.Fatalf("profilo privato non cercabile: %d", len(r))
	}
	if r := e.do("GET", "/v1/tracks/search?driver=bru", bruno, nil).arr(); len(r) != 1 {
		t.Fatalf("sé stessi sì: %d", len(r))
	}
	all := e.do("GET", "/v1/tracks", "", nil).arr()
	if len(all) != 2 || all[0]["track"].(map[string]any)["name"] != "Monte Lontano" {
		t.Fatalf("tutti, dal più recente: %v", len(all))
	}
	top := all[1]["topTimes"].([]any)
	if len(top) != 1 || top[0].(map[string]any)["driver"] != privateDriver {
		t.Fatalf("topTimes con pilota privato: %v", top)
	}
}

/* ------------------------------ Account ------------------------------- */

func TestEliminazioni(t *testing.T) {
	e := newEnv(t)
	anna, bruno := e.user("anna"), e.user("bruno")
	id1 := e.publish(anna, trackJSON("Uno"))["id"].(string)
	id2 := e.publish(bruno, trackJSON("Due"))["id"].(string)
	e.send(bruno, id1, 1, nil, 30000)
	e.send(bruno, id2, 1, nil, 30000)
	e.send(anna, id2, 1, nil, 31000)

	r := e.expect(e.do("DELETE", "/v1/users/me/laps", bruno, nil), 200).obj()
	if num(r["deleted"]) != 2 {
		t.Fatalf("giri eliminati: %v", r)
	}
	// Foto profilo.
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3}
	e.expect(e.do("PUT", "/v1/users/me/avatar", anna, []byte("non è un jpeg")), 400)
	url := e.expect(e.do("PUT", "/v1/users/me/avatar", anna, jpeg), 200).obj()["avatarUrl"].(string)
	name := url[len("https://gps.example"):]
	if got := e.expect(e.do("GET", name, "", nil), 200).body; !bytes.Equal(got, jpeg) {
		t.Fatal("foto diversa")
	}
	// Elimina profilo: tracciati, giri, foto; il nickname torna libero.
	e.expect(e.do("DELETE", "/v1/users/me", anna, nil), 204)
	e.expect(e.do("GET", "/v1/tracks/"+id1, "", nil), 404)
	e.expect(e.do("GET", name, "", nil), 404)
	if len(e.do("GET", "/v1/tracks/"+id2+"/leaderboard", "", nil).arr()) != 0 {
		t.Fatal("i giri di anna devono sparire")
	}
	if r := e.do("GET", "/v1/nicknames/anna/availability", "", nil).obj(); r["available"] != true {
		t.Fatal("anna torna libero")
	}
	var n int
	e.app.db.read.QueryRow(`SELECT (SELECT count(*) FROM sessioni) + (SELECT count(*) FROM migliori_settori)
		+ (SELECT count(*) FROM tracciati_geometria WHERE tracciato_id NOT IN (SELECT id FROM tracciati))`).Scan(&n)
	if n != 1 { // resta solo la sessione di bruno
		t.Fatalf("righe orfane: %d", n)
	}
}

/* ---------------------- Server occupato e scritture ------------------- */

func TestCodaPienaRisponde503(t *testing.T) {
	e := newEnv(t)
	tok := e.user("anna")
	id := e.publish(tok, trackJSON("Pista"))["id"].(string)
	// Coda piccolissima e scrittore bloccato da un lavoro lento.
	old := e.app.db.W
	e.app.db.W = NewWriter(e.app.db.write, 0, 1)
	defer func() { e.app.db.W.Close(); e.app.db.W = old }()
	release := make(chan struct{})
	go e.app.db.W.Do(context.Background(), func(*sql.Tx) error { <-release; return nil })
	time.Sleep(50 * time.Millisecond)
	go e.app.db.W.Do(context.Background(), func(*sql.Tx) error { return nil }) // occupa l'unico posto in coda
	time.Sleep(50 * time.Millisecond)

	r := e.do("POST", "/v1/tracks/"+id+"/laps", tok, lap(30000, 1, nil, 30000))
	if r.status != 503 || r.header.Get("Retry-After") == "" || r.obj()["error"] != "busy" {
		t.Fatalf("atteso 503 con Retry-After: %d %s", r.status, r.body)
	}
	close(release)
	time.Sleep(50 * time.Millisecond)
	e.send(tok, id, 1, nil, 30000) // ora passa
}

func TestLimiterAttendePrimaDiRifiutare(t *testing.T) {
	slow := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(150 * time.Millisecond)
		w.WriteHeader(200)
	})
	srv := httptest.NewServer(NewLimiter(1, 400*time.Millisecond).Wrap(slow))
	defer srv.Close()
	var wg sync.WaitGroup
	codes := make([]int, 4)
	for i := range codes {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			time.Sleep(time.Duration(i) * 5 * time.Millisecond)
			r, err := http.Get(srv.URL)
			if err == nil {
				codes[i] = r.StatusCode
				r.Body.Close()
			}
		}(i)
	}
	wg.Wait()
	ok, busy := 0, 0
	for _, c := range codes {
		switch c {
		case 200:
			ok++
		case 503:
			busy++
		}
	}
	// 1 posto, 150 ms a richiesta, 400 ms di attesa: le prime 3 passano aspettando.
	if ok != 3 || busy != 1 {
		t.Fatalf("passate %d, rifiutate %d (%v)", ok, busy, codes)
	}
}

func TestScrittureRaggruppate(t *testing.T) {
	e := newEnv(t)
	// Scrittore che attende 100 ms per raggruppare.
	old := e.app.db.W
	e.app.db.W = NewWriter(e.app.db.write, 100*time.Millisecond, 256)
	defer func() { e.app.db.W.Close(); e.app.db.W = old }()

	owner := e.user("autore")
	id := e.publish(owner, trackJSON("Pista", sp{"P1", false}))["id"].(string)
	const n = 40
	toks := make([]string, n)
	for i := range toks {
		toks[i] = e.user(fmt.Sprintf("pilota%02d", i))
	}
	before := e.app.db.W.Batches()
	start := time.Now()
	var wg sync.WaitGroup
	var errMu sync.Mutex
	var errs []error
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			r := e.do("POST", "/v1/tracks/"+id+"/laps", toks[i], lap(int64(40000+i*10), 1, nil, 20000, int64(20000+i*10)))
			if r.status != 201 {
				errMu.Lock()
				errs = append(errs, errors.New(string(r.body)))
				errMu.Unlock()
			}
		}(i)
	}
	wg.Wait()
	if len(errs) > 0 {
		t.Fatalf("invii falliti: %v", errs)
	}
	board := e.do("GET", "/v1/tracks/"+id+"/leaderboard", "", nil).arr()
	if len(board) != n || num(board[0]["lapMs"]) != 40000 || num(board[n-1]["rank"]) != n {
		t.Fatalf("classifica: %d righe", len(board))
	}
	tx := e.app.db.W.Batches() - before
	if tx > n/4 {
		t.Fatalf("%d giri in %d transazioni: le scritture non sono raggruppate", n, tx)
	}
	t.Logf("%d giri salvati in %d transazioni, %v", n, tx, time.Since(start))
}

func TestBackup(t *testing.T) {
	e := newEnv(t)
	tok := e.user("anna")
	id := e.publish(tok, trackJSON("Pista"))["id"].(string)
	e.send(tok, id, 1, nil, 30000)
	dir := t.TempDir()
	target := filepath.Join(dir, "copia.db")
	// A server acceso, due volte (la seconda sovrascrive).
	for i := 0; i < 2; i++ {
		if err := backupTo(filepath.Join(e.app.avatarsDir, "test.db"), target); err != nil {
			t.Fatal(err)
		}
	}
	db, err := sql.Open("sqlite", dsn(target, true))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var n int
	if err := db.QueryRow(`SELECT count(*) FROM risultati`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("copia: %d %v", n, err)
	}
}

/* ------------------------- Coerenza con l'app ------------------------- */

func TestValidazioneTracciato(t *testing.T) {
	e := newEnv(t)
	tok := e.user("anna")
	bad := trackJSON("")
	e.expect(e.do("POST", "/v1/tracks", tok, map[string]any{"track": bad}), 400)
	open := trackJSON("Aperto")
	open["type"] = "OPEN"
	e.expect(e.do("POST", "/v1/tracks", tok, map[string]any{"track": open}), 400) // manca il traguardo
	open["finish"] = map[string]any{"lat": 45.1, "lon": 9.1, "heading": 0, "width": 30}
	e.publish(tok, open)
	e.expect(e.do("POST", "/v1/tracks", "", map[string]any{"track": open}), 401)
	// L'ultimo parziale contrassegnato neutro non è un ingresso valido (come Neutral.isEntry).
	p := e.publish(tok, trackJSON("Coda", sp{"P1", false}, sp{"P2", true}))
	if num(p["timedSectors"]) != 3 {
		t.Fatalf("ultimo parziale neutro ignorato: %v", p["timedSectors"])
	}
}

/* ------------------------------ Amici --------------------------------- */

func TestAmici(t *testing.T) {
	e := newEnv(t)
	me, anna, bruno, carla := e.user("io_rc"), e.user("anna"), e.user("bruno"), e.user("carla")
	e.user("dario") // amico senza tempi
	id := e.publish(me, trackJSON("Pista"))["id"].(string)
	e.send(me, id, 1, nil, 31000)
	e.send(anna, id, 2, nil, 30000)
	e.send(bruno, id, 3, nil, 33000)
	e.send(carla, id, 4, nil, 29000)

	uid := func(nick string) string {
		r := e.expect(e.do("GET", "/v1/drivers/"+nick, "", nil), 200).obj()
		return r["userId"].(string)
	}
	ua, ub, uc, ud := uid("ANNA"), uid("bruno"), uid("carla"), uid("dario")
	e.expect(e.do("GET", "/v1/drivers/nessuno", "", nil), 404)

	// Carla privata: non si trova più e non compare tra gli amici.
	e.expect(e.do("PATCH", "/v1/users/me", carla, map[string]any{"visibility": "PRIVATE"}), 200)
	e.expect(e.do("GET", "/v1/drivers/carla", "", nil), 404)
	e.expect(e.do("GET", "/v1/drivers/carla", carla, nil), 200) // sé stessi sì

	// Anna cambia nome: il server restituisce quello nuovo.
	e.expect(e.do("PATCH", "/v1/users/me", anna, map[string]any{"nickname": "anna.gp"}), 200)

	body := map[string]any{"userIds": []string{ud, ub, ua, uc, ua, "inesistente"}}
	r := e.expect(e.do("POST", "/v1/tracks/"+id+"/friends", me, body), 200).obj()
	if num(r["drivers"]) != 4 || num(r["me"].(map[string]any)["rank"]) != 3 {
		t.Fatalf("classifica: %v", r)
	}
	fs := r["friends"].([]any)
	if len(fs) != 5 {
		t.Fatalf("amici (senza doppioni): %d", len(fs))
	}
	f0, f1, f2 := fs[0].(map[string]any), fs[1].(map[string]any), fs[2].(map[string]any)
	if f0["nickname"] != "anna.gp" || num(f0["entry"].(map[string]any)["rank"]) != 2 || num(f0["gapMs"]) != -1000 {
		t.Fatalf("anna: %v", f0)
	}
	if f1["nickname"] != "bruno" || num(f1["gapMs"]) != 2000 {
		t.Fatalf("bruno: %v", f1)
	}
	if f2["nickname"] != "dario" || f2["found"] != true || f2["entry"] != nil {
		t.Fatalf("dario senza tempi: %v", f2)
	}
	for _, f := range fs[3:] {
		m := f.(map[string]any)
		if m["found"] != false || m["nickname"] != nil || m["entry"] != nil {
			t.Fatalf("privato o inesistente: %v", m)
		}
	}
	// Senza accesso: tempi sì, distacchi no.
	r = e.expect(e.do("POST", "/v1/tracks/"+id+"/friends", "", body), 200).obj()
	if r["me"] != nil || r["friends"].([]any)[0].(map[string]any)["gapMs"] != nil {
		t.Fatalf("senza accesso: %v", r)
	}
	// Troppi amici.
	many := make([]string, MaxFriends+1)
	for i := range many {
		many[i] = fmt.Sprint(i)
	}
	e.expect(e.do("POST", "/v1/tracks/"+id+"/friends", me, map[string]any{"userIds": many}), 400)
}

func TestLimitePerUtenteDietroTunnel(t *testing.T) {
	e := newEnv(t)
	anna, bruno := e.user("anna"), e.user("bruno")
	rl := NewRateLimit(0.001, 3) // 3 richieste, poi stop
	h := rl.Wrap(false, e.app.validToken, e.app.Routes())
	srv := httptest.NewServer(h)
	defer srv.Close()
	get := func(tok string) int {
		req, _ := http.NewRequest("GET", srv.URL+"/v1/health", nil)
		if tok != "" {
			req.Header.Set("Authorization", "Bearer "+tok)
		}
		r, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		r.Body.Close()
		return r.StatusCode
	}
	// Tutti dallo stesso IP (come dietro un tunnel): ognuno ha il suo limite.
	for i := 0; i < 3; i++ {
		if get(anna) != 200 {
			t.Fatal("anna dentro il limite")
		}
	}
	if get(anna) != 429 {
		t.Fatal("anna oltre il limite")
	}
	if get(bruno) != 200 {
		t.Fatal("bruno ha un limite suo")
	}
	// Token inventati: contano sull'IP, non aprono limiti nuovi.
	for i := 0; i < 3; i++ {
		get(fmt.Sprintf("finto%d", i))
	}
	if get("finto-altro") != 429 || get("") != 429 {
		t.Fatal("i token inventati devono condividere il limite dell'IP")
	}
}
