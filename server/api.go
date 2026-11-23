package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
)

const (
	maxTrackBytes  = 2 << 20 // geometria di un tracciato (percorso compreso)
	maxSmallBody   = 16 << 10
	maxAvatarBytes = 1 << 20
	listLimit      = 50
)

type App struct {
	db         *Store
	avatarsDir string
	publicURL  string
	loginRL    *RateLimit
	now        func() int64
}

// apiError: errore con stato HTTP e messaggio per l'utente (formato del contratto).
type apiError struct {
	status  int
	code    string
	message string
}

func (e *apiError) Error() string { return e.message }

func bad(code, msg string) error      { return &apiError{http.StatusBadRequest, code, msg} }
func conflict(code, msg string) error { return &apiError{http.StatusConflict, code, msg} }

var (
	errNotFound  = &apiError{http.StatusNotFound, "not_found", "Non trovato o non più pubblicato"}
	errForbidden = &apiError{http.StatusForbidden, "forbidden", "Operazione non consentita"}
)

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]string{"error": code, "message": msg})
}

func internal(w http.ResponseWriter, err error) {
	log.Printf("errore: %v", err)
	writeErr(w, http.StatusInternalServerError, "internal", "Errore del server, riprova più tardi")
}

// fail traduce un errore in risposta HTTP.
func fail(w http.ResponseWriter, err error) {
	var ae *apiError
	switch {
	case errors.As(err, &ae):
		writeErr(w, ae.status, ae.code, ae.message)
	case errors.Is(err, ErrBusy):
		busy(w)
	default:
		internal(w, err)
	}
}

func decode(r *http.Request, max int64, v any) error {
	if err := json.NewDecoder(io.LimitReader(r.Body, max)).Decode(v); err != nil {
		return bad("invalid_body", "Richiesta non valida")
	}
	return nil
}

func (a *App) Routes() *http.ServeMux {
	m := http.NewServeMux()
	m.HandleFunc("GET /v1/health", a.health)

	m.HandleFunc("GET /v1/nicknames/{nickname}/availability", a.nicknameAvailability)
	m.HandleFunc("POST /v1/users", a.createUser)
	m.HandleFunc("POST /v1/sessions", a.login)
	m.HandleFunc("DELETE /v1/sessions/current", a.requireAuth(a.logout))
	m.HandleFunc("GET /v1/users/me", a.requireAuth(a.me))
	m.HandleFunc("PATCH /v1/users/me", a.requireAuth(a.patchMe))
	m.HandleFunc("DELETE /v1/users/me", a.requireAuth(a.deleteMe))
	m.HandleFunc("PUT /v1/users/me/avatar", a.requireAuth(a.putAvatar))
	m.HandleFunc("PUT /v1/users/me/settings", a.requireAuth(a.putSettings))
	m.HandleFunc("POST /v1/users/me/devices", a.requireAuth(a.addDevice))
	m.HandleFunc("DELETE /v1/users/me/laps", a.requireAuth(a.deleteMyLaps))
	m.HandleFunc("GET /v1/users/me/records", a.requireAuth(a.myRecords))
	m.HandleFunc("GET /v1/avatars/{name}", a.getAvatar)
	m.HandleFunc("GET /v1/drivers", a.searchDrivers)
	m.HandleFunc("GET /v1/drivers/{nickname}", a.findDriver)

	m.HandleFunc("POST /v1/tracks", a.requireAuth(a.publishTrack))
	m.HandleFunc("GET /v1/tracks", a.listTracks)
	m.HandleFunc("GET /v1/tracks/nearby", a.nearby)
	m.HandleFunc("GET /v1/tracks/search", a.search)
	m.HandleFunc("GET /v1/tracks/{id}", a.getTrack)
	m.HandleFunc("DELETE /v1/tracks/{id}", a.requireAuth(a.deleteTrack))
	m.HandleFunc("GET /v1/tracks/{id}/leaderboard", a.leaderboard)
	m.HandleFunc("GET /v1/tracks/{id}/best", a.best)
	m.HandleFunc("GET /v1/tracks/{id}/compare", a.requireAuth(a.compare))
	m.HandleFunc("POST /v1/tracks/{id}/laps", a.requireAuth(a.submitLap))
	m.HandleFunc("POST /v1/tracks/{id}/friends", a.friendTimes)
	m.HandleFunc("DELETE /v1/laps/{lapId}", a.requireAuth(a.deleteLap))
	return m
}

func (a *App) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"ok": true, "writeQueue": a.db.W.Pending(), "writeBatches": a.db.W.Batches()})
}

/* ------------------------------- Utenti ------------------------------- */

func (a *App) nicknameAvailability(w http.ResponseWriter, r *http.Request) {
	me, err := a.authOptional(r)
	if err != nil {
		internal(w, err)
		return
	}
	var self int64
	if me != nil {
		self = me.ID
	}
	res, err := NicknameAvailability(a.db.read, r.PathValue("nickname"), self)
	if err != nil {
		internal(w, err)
		return
	}
	writeJSON(w, 200, res)
}

type userBody struct {
	Nickname        *string `json:"nickname"`
	Email           *string `json:"email"`
	Password        *string `json:"password"`
	CurrentPassword *string `json:"currentPassword"`
	DisplayName     *string `json:"displayName"`
	Visibility      *string `json:"visibility"`
}

// Controlli sul nickname dentro la transazione dello scrittore: due
// registrazioni simultanee dello stesso nome non possono passare entrambe.
func checkNicknameTx(tx *sql.Tx, raw string, selfID int64) (string, error) {
	res, err := NicknameAvailability(tx, raw, selfID)
	if err != nil {
		return "", err
	}
	if res.Available {
		return res.Nickname, nil
	}
	if _, why := ValidateNickname(raw); why != "" {
		return "", bad("invalid_nickname", why)
	}
	return "", conflict("nickname_taken", *res.Reason)
}

func checkEmailTx(tx *sql.Tx, email string, selfID int64) error {
	var id int64
	err := tx.QueryRow(`SELECT id FROM piloti WHERE email = ? COLLATE NOCASE`, email).Scan(&id)
	if err == nil && id != selfID {
		return conflict("email_taken", "Email già registrata")
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	return nil
}

func cleanDisplayName(s *string) (any, error) {
	if s == nil {
		return nil, nil
	}
	v := strings.TrimSpace(*s)
	if v == "" {
		return nil, nil
	}
	if utf8.RuneCountInString(v) > 40 {
		return nil, bad("invalid_name", "Nome troppo lungo (massimo 40 caratteri)")
	}
	return v, nil
}

func (a *App) createUser(w http.ResponseWriter, r *http.Request) {
	var b userBody
	if err := decode(r, maxSmallBody, &b); err != nil {
		fail(w, err)
		return
	}
	if b.Nickname == nil {
		fail(w, bad("invalid_nickname", "Nickname mancante"))
		return
	}
	var email any
	if b.Email != nil && strings.TrimSpace(*b.Email) != "" {
		e := strings.TrimSpace(*b.Email)
		if !validEmail(e) {
			fail(w, bad("invalid_email", "Email non valida"))
			return
		}
		email = e
	}
	var pwHash any
	if b.Password != nil {
		if why := validPassword(*b.Password); why != "" {
			fail(w, bad("invalid_password", why))
			return
		}
		h, err := hashPassword(*b.Password) // fuori dallo scrittore: è il lavoro più pesante
		if err != nil {
			internal(w, err)
			return
		}
		pwHash = h
	}
	name, err := cleanDisplayName(b.DisplayName)
	if err != nil {
		fail(w, err)
		return
	}
	var out map[string]any
	err = a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		nick, err := checkNicknameTx(tx, *b.Nickname, 0)
		if err != nil {
			return err
		}
		if email != nil {
			if err := checkEmailTx(tx, email.(string), 0); err != nil {
				return err
			}
		}
		now := a.now()
		res, err := tx.Exec(`INSERT INTO piloti (uid, nickname, email, password_hash, nome, creato_il) VALUES (?, ?, ?, ?, ?, ?)`,
			newUID(), nick, email, pwHash, name, now)
		if err != nil {
			return err
		}
		id, _ := res.LastInsertId()
		tok, err := createSession(tx, id, now)
		if err != nil {
			return err
		}
		p, err := pilotByID(tx, id)
		if err != nil {
			return err
		}
		out = p.account(tok)
		return nil
	})
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, out)
}

// Hash di riferimento: con un utente inesistente si esegue comunque un
// confronto bcrypt, così il tempo di risposta non rivela se il nome esiste.
var dummyHash, _ = bcrypt.GenerateFromPassword([]byte("gpstelemetry-dummy"), bcryptCost)

func (a *App) login(w http.ResponseWriter, r *http.Request) {
	if !a.loginRL.Allow(clientIP(r, trustProxyFlag)) {
		w.Header().Set("Retry-After", "10")
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "Troppi tentativi, riprova tra poco")
		return
	}
	var b struct {
		Login    string `json:"login"`
		Password string `json:"password"`
	}
	if err := decode(r, maxSmallBody, &b); err != nil {
		fail(w, err)
		return
	}
	login := strings.TrimSpace(b.Login)
	var id int64
	var hash string
	err := a.db.read.QueryRow(`SELECT id, password_hash FROM piloti
		WHERE (nickname = ?1 COLLATE NOCASE OR email = ?1 COLLATE NOCASE) AND password_hash IS NOT NULL LIMIT 1`, login).Scan(&id, &hash)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		internal(w, err)
		return
	}
	if id == 0 {
		bcrypt.CompareHashAndPassword(dummyHash, []byte(b.Password))
		writeErr(w, http.StatusUnauthorized, "invalid_credentials", "Nickname, email o password errati")
		return
	}
	if bcrypt.CompareHashAndPassword([]byte(hash), []byte(b.Password)) != nil {
		writeErr(w, http.StatusUnauthorized, "invalid_credentials", "Nickname, email o password errati")
		return
	}
	var out map[string]any
	err = a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		tok, err := createSession(tx, id, a.now())
		if err != nil {
			return err
		}
		p, err := pilotByID(tx, id)
		if err != nil || p == nil {
			return errNotFound
		}
		out = p.account(tok)
		return nil
	})
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, 200, out)
}

func bearer(r *http.Request) string {
	return strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
}

func (a *App) logout(w http.ResponseWriter, r *http.Request, me *Pilot) {
	h := tokenHash(bearer(r))
	if err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		_, err := tx.Exec(`DELETE FROM sessioni WHERE token_hash = ?`, h)
		return err
	}); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) me(w http.ResponseWriter, r *http.Request, me *Pilot) {
	writeJSON(w, 200, me.account(""))
}

func (a *App) patchMe(w http.ResponseWriter, r *http.Request, me *Pilot) {
	var b userBody
	if err := decode(r, maxSmallBody, &b); err != nil {
		fail(w, err)
		return
	}
	if b.Visibility != nil && *b.Visibility != "PUBLIC" && *b.Visibility != "PRIVATE" {
		fail(w, bad("invalid_visibility", "Visibilità non valida"))
		return
	}
	var newHash string
	if b.Password != nil {
		if why := validPassword(*b.Password); why != "" {
			fail(w, bad("invalid_password", why))
			return
		}
		if me.HasPassword {
			var cur string
			if err := a.db.read.QueryRow(`SELECT password_hash FROM piloti WHERE id = ?`, me.ID).Scan(&cur); err != nil {
				internal(w, err)
				return
			}
			if b.CurrentPassword == nil || bcrypt.CompareHashAndPassword([]byte(cur), []byte(*b.CurrentPassword)) != nil {
				writeErr(w, http.StatusForbidden, "wrong_password", "La password attuale non è corretta")
				return
			}
		}
		h, err := hashPassword(*b.Password)
		if err != nil {
			internal(w, err)
			return
		}
		newHash = h
	}
	var email any
	if b.Email != nil {
		if e := strings.TrimSpace(*b.Email); e != "" {
			if !validEmail(e) {
				fail(w, bad("invalid_email", "Email non valida"))
				return
			}
			email = e
		}
	}
	name, err := cleanDisplayName(b.DisplayName)
	if err != nil {
		fail(w, err)
		return
	}
	var out map[string]any
	err = a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		if b.Nickname != nil {
			nick, err := checkNicknameTx(tx, *b.Nickname, me.ID)
			if err != nil {
				return err
			}
			if _, err := tx.Exec(`UPDATE piloti SET nickname = ? WHERE id = ?`, nick, me.ID); err != nil {
				return err
			}
		}
		if b.Visibility != nil {
			if _, err := tx.Exec(`UPDATE piloti SET visibilita = ? WHERE id = ?`, *b.Visibility, me.ID); err != nil {
				return err
			}
		}
		if b.Email != nil {
			if email != nil {
				if err := checkEmailTx(tx, email.(string), me.ID); err != nil {
					return err
				}
			}
			if _, err := tx.Exec(`UPDATE piloti SET email = ? WHERE id = ?`, email, me.ID); err != nil {
				return err
			}
		}
		if b.DisplayName != nil {
			if _, err := tx.Exec(`UPDATE piloti SET nome = ? WHERE id = ?`, name, me.ID); err != nil {
				return err
			}
		}
		if newHash != "" {
			if _, err := tx.Exec(`UPDATE piloti SET password_hash = ? WHERE id = ?`, newHash, me.ID); err != nil {
				return err
			}
			// Cambio password: si chiudono gli altri accessi.
			if _, err := tx.Exec(`DELETE FROM sessioni WHERE pilota_id = ? AND token_hash <> ?`, me.ID, tokenHash(bearer(r))); err != nil {
				return err
			}
		}
		p, err := pilotByID(tx, me.ID)
		if err != nil {
			return err
		}
		out = p.account("")
		return nil
	})
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, 200, out)
}

func (a *App) avatarPath(url sql.NullString) string {
	if !url.Valid {
		return ""
	}
	i := strings.LastIndex(url.String, "/v1/avatars/")
	if i < 0 {
		return ""
	}
	name := url.String[i+len("/v1/avatars/"):]
	if !avatarName.MatchString(name) {
		return ""
	}
	return filepath.Join(a.avatarsDir, name)
}

func (a *App) deleteMe(w http.ResponseWriter, r *http.Request, me *Pilot) {
	if err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		// Le cascate eliminano sessioni, tracciati (con classifiche), risultati e settori.
		_, err := tx.Exec(`DELETE FROM piloti WHERE id = ?`, me.ID)
		return err
	}); err != nil {
		fail(w, err)
		return
	}
	if p := a.avatarPath(me.Avatar); p != "" {
		os.Remove(p)
	}
	w.WriteHeader(http.StatusNoContent)
}

var avatarName = regexp.MustCompile(`^[A-Za-z0-9_-]{8,64}\.jpg$`)

func (a *App) putAvatar(w http.ResponseWriter, r *http.Request, me *Pilot) {
	data, err := io.ReadAll(io.LimitReader(r.Body, maxAvatarBytes+1))
	if err != nil {
		fail(w, bad("invalid_body", "Immagine non ricevuta"))
		return
	}
	if len(data) > maxAvatarBytes {
		writeErr(w, http.StatusRequestEntityTooLarge, "too_large", "Immagine troppo grande (massimo 1 MB)")
		return
	}
	if len(data) < 4 || data[0] != 0xFF || data[1] != 0xD8 || data[2] != 0xFF {
		fail(w, bad("invalid_image", "Serve un'immagine JPEG"))
		return
	}
	// Nome nuovo a ogni caricamento: i telefoni possono tenere in cache la foto per sempre.
	name := me.UID + "-" + newUID()[:8] + ".jpg"
	tmp := filepath.Join(a.avatarsDir, name+".tmp")
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		internal(w, err)
		return
	}
	if err := os.Rename(tmp, filepath.Join(a.avatarsDir, name)); err != nil {
		os.Remove(tmp)
		internal(w, err)
		return
	}
	url := strings.TrimRight(a.publicURL, "/") + "/v1/avatars/" + name
	if err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		_, err := tx.Exec(`UPDATE piloti SET foto_url = ? WHERE id = ?`, url, me.ID)
		return err
	}); err != nil {
		os.Remove(filepath.Join(a.avatarsDir, name))
		fail(w, err)
		return
	}
	if old := a.avatarPath(me.Avatar); old != "" {
		os.Remove(old)
	}
	writeJSON(w, 200, map[string]string{"avatarUrl": url})
}

func (a *App) getAvatar(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if !avatarName.MatchString(name) {
		writeErr(w, 404, "not_found", "Foto non trovata")
		return
	}
	f, err := os.Open(filepath.Join(a.avatarsDir, name))
	if err != nil {
		writeErr(w, 404, "not_found", "Foto non trovata")
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	http.ServeContent(w, r, name, st.ModTime(), f)
}

func (a *App) putSettings(w http.ResponseWriter, r *http.Request, me *Pilot) {
	var b struct {
		NotifyRecordBeaten *bool   `json:"notifyRecordBeaten"`
		NotifyFriendsLaps  *bool   `json:"notifyFriendsLaps"`
		LeaderboardMode    *string `json:"leaderboardMode"` // accettato; il server conserva solo i record
	}
	if err := decode(r, maxSmallBody, &b); err != nil {
		fail(w, err)
		return
	}
	var out map[string]any
	if err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		if _, err := tx.Exec(`UPDATE piloti SET notifica_record = coalesce(?, notifica_record),
			notifica_amici = coalesce(?, notifica_amici) WHERE id = ?`, b.NotifyRecordBeaten, b.NotifyFriendsLaps, me.ID); err != nil {
			return err
		}
		p, err := pilotByID(tx, me.ID)
		if err != nil {
			return err
		}
		out = p.account("")["settings"].(map[string]any)
		return nil
	}); err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, 200, out)
}

func (a *App) addDevice(w http.ResponseWriter, r *http.Request, me *Pilot) {
	var b struct {
		PushToken string `json:"pushToken"`
		Platform  string `json:"platform"`
	}
	if err := decode(r, maxSmallBody, &b); err != nil {
		fail(w, err)
		return
	}
	if b.PushToken == "" || len(b.PushToken) > 4096 || (b.Platform != "android" && b.Platform != "ios") {
		fail(w, bad("invalid_device", "Dispositivo non valido"))
		return
	}
	if err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		_, err := tx.Exec(`INSERT INTO dispositivi (push_token, pilota_id, piattaforma, creato_il) VALUES (?, ?, ?, ?)
			ON CONFLICT (push_token) DO UPDATE SET pilota_id = excluded.pilota_id, piattaforma = excluded.piattaforma`,
			b.PushToken, me.ID, b.Platform, a.now())
		return err
	}); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) deleteMyLaps(w http.ResponseWriter, r *http.Request, me *Pilot) {
	var n int64
	if err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		res, err := tx.Exec(`DELETE FROM risultati WHERE pilota_id = ?`, me.ID)
		if err != nil {
			return err
		}
		n, _ = res.RowsAffected()
		_, err = tx.Exec(`DELETE FROM migliori_settori WHERE pilota_id = ?`, me.ID)
		return err
	}); err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, 200, map[string]int64{"deleted": n})
}

func (a *App) myRecords(w http.ResponseWriter, r *http.Request, me *Pilot) {
	recs, err := PersonalRecords(a.db.read, me.ID)
	if err != nil {
		internal(w, err)
		return
	}
	writeJSON(w, 200, recs)
}

/* ------------------------------ Tracciati ----------------------------- */

func (a *App) publishTrack(w http.ResponseWriter, r *http.Request, me *Pilot) {
	var b struct {
		Track json.RawMessage `json:"track"`
	}
	if err := decode(r, maxTrackBytes+4096, &b); err != nil {
		fail(w, err)
		return
	}
	info, err := ParseTrack(b.Track)
	if err != nil {
		fail(w, bad("invalid_track", err.Error()))
		return
	}
	var uid string
	reset := false
	err = a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		now := a.now()
		var existing *TrackRow
		if id := info.Geom.RemoteID; id != nil && *id != "" {
			t, err := trackByUID(tx, *id)
			if err != nil {
				return err
			}
			if t != nil && t.AuthorID == me.ID {
				existing = t
			}
		}
		uid = newUID()
		if existing != nil {
			uid = existing.UID
		}
		info.Raw["published"] = true
		info.Raw["remoteId"] = uid
		info.Raw["remoteAuthor"] = nil
		geom, err := json.Marshal(info.Raw)
		if err != nil {
			return err
		}
		names, _ := json.Marshal(info.SectorNames)
		start := info.Geom.Start
		if existing == nil {
			res, err := tx.Exec(`INSERT INTO tracciati (uid, autore_id, id_locale, nome, tipo, settori_totali, maschera_neutri,
					num_settori, nomi_settori, impronta, lat, lon, pubblicato_il, aggiornato_il)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				uid, me.ID, info.Geom.ID, info.Geom.Name, info.Geom.Type, info.TotalSectors, info.NeutralMask,
				info.TimedSectors, string(names), info.Fingerprint, start.Lat, start.Lon, now, now)
			if err != nil {
				return err
			}
			id, _ := res.LastInsertId()
			_, err = tx.Exec(`INSERT INTO tracciati_geometria (tracciato_id, json) VALUES (?, ?)`, id, string(geom))
			return err
		}
		// Linee spostate: i tempi registrati non sono più confrontabili.
		if existing.Fingerprint != info.Fingerprint {
			reset = true
			if _, err := tx.Exec(`DELETE FROM risultati WHERE tracciato_id = ?`, existing.ID); err != nil {
				return err
			}
			if _, err := tx.Exec(`DELETE FROM migliori_settori WHERE tracciato_id = ?`, existing.ID); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(`UPDATE tracciati SET id_locale = ?, nome = ?, tipo = ?, settori_totali = ?, maschera_neutri = ?,
				num_settori = ?, nomi_settori = ?, impronta = ?, lat = ?, lon = ?, aggiornato_il = ? WHERE id = ?`,
			info.Geom.ID, info.Geom.Name, info.Geom.Type, info.TotalSectors, info.NeutralMask, info.TimedSectors,
			string(names), info.Fingerprint, start.Lat, start.Lon, now, existing.ID); err != nil {
			return err
		}
		_, err = tx.Exec(`UPDATE tracciati_geometria SET json = ? WHERE tracciato_id = ?`, string(geom), existing.ID)
		return err
	})
	if err != nil {
		fail(w, err)
		return
	}
	t, err := trackByUID(a.db.read, uid)
	if err != nil || t == nil {
		internal(w, fmt.Errorf("tracciato appena pubblicato non trovato: %v", err))
		return
	}
	out, err := PublishedTrackJSON(a.db.read, t, me.ID, nil)
	if err != nil {
		internal(w, err)
		return
	}
	out["leaderboardReset"] = reset
	writeJSON(w, http.StatusCreated, out)
}

func (a *App) deleteTrack(w http.ResponseWriter, r *http.Request, me *Pilot) {
	uid := r.PathValue("id")
	err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		t, err := trackByUID(tx, uid)
		if err != nil {
			return err
		}
		if t == nil {
			return errNotFound
		}
		if t.AuthorID != me.ID {
			return errForbidden
		}
		_, err = tx.Exec(`DELETE FROM tracciati WHERE id = ?`, t.ID)
		return err
	})
	if err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// track: tracciato dal percorso della richiesta, con l'utente (facoltativo).
func (a *App) track(w http.ResponseWriter, r *http.Request) (*TrackRow, *Pilot, bool) {
	me, err := a.authOptional(r)
	if err != nil {
		internal(w, err)
		return nil, nil, false
	}
	t, err := trackByUID(a.db.read, r.PathValue("id"))
	if err != nil {
		internal(w, err)
		return nil, nil, false
	}
	if t == nil {
		fail(w, errNotFound)
		return nil, nil, false
	}
	return t, me, true
}

func meID(p *Pilot) int64 {
	if p == nil {
		return 0
	}
	return p.ID
}

func (a *App) getTrack(w http.ResponseWriter, r *http.Request) {
	t, me, ok := a.track(w, r)
	if !ok {
		return
	}
	out, err := PublishedTrackJSON(a.db.read, t, meID(me), nil)
	if err != nil {
		internal(w, err)
		return
	}
	writeJSON(w, 200, out)
}

func (a *App) writeTracks(w http.ResponseWriter, ts []*TrackRow, dists []float64, me *Pilot) {
	out := make([]map[string]any, 0, len(ts))
	for i, t := range ts {
		var d *float64
		if dists != nil {
			d = &dists[i]
		}
		p, err := PublishedTrackJSON(a.db.read, t, meID(me), d)
		if err != nil {
			internal(w, err)
			return
		}
		out = append(out, p)
	}
	writeJSON(w, 200, out)
}

func (a *App) listTracks(w http.ResponseWriter, r *http.Request) {
	me, err := a.authOptional(r)
	if err != nil {
		internal(w, err)
		return
	}
	ts, err := queryTracks(a.db.read, fmt.Sprintf(`ORDER BY t.pubblicato_il DESC LIMIT %d`, listLimit))
	if err != nil {
		internal(w, err)
		return
	}
	a.writeTracks(w, ts, nil, me)
}

func (a *App) nearby(w http.ResponseWriter, r *http.Request) {
	me, err := a.authOptional(r)
	if err != nil {
		internal(w, err)
		return
	}
	q := r.URL.Query()
	lat, e1 := strconv.ParseFloat(q.Get("lat"), 64)
	lon, e2 := strconv.ParseFloat(q.Get("lon"), 64)
	if e1 != nil || e2 != nil || lat < -90 || lat > 90 || lon < -180 || lon > 180 {
		fail(w, bad("invalid_position", "Posizione non valida"))
		return
	}
	radius := 50.0
	if s := q.Get("radiusKm"); s != "" {
		if v, err := strconv.ParseFloat(s, 64); err == nil && v > 0 {
			radius = min(v, 500)
		}
	}
	ts, ds, err := Nearby(a.db.read, lat, lon, radius, listLimit)
	if err != nil {
		internal(w, err)
		return
	}
	a.writeTracks(w, ts, ds, me)
}

func (a *App) search(w http.ResponseWriter, r *http.Request) {
	me, err := a.authOptional(r)
	if err != nil {
		internal(w, err)
		return
	}
	q := r.URL.Query()
	lim := fmt.Sprintf(` ORDER BY t.pubblicato_il DESC LIMIT %d`, listLimit)
	var ts []*TrackRow
	switch {
	case strings.TrimSpace(q.Get("name")) != "":
		ts, err = queryTracks(a.db.read, `WHERE t.nome LIKE ? ESCAPE '\'`+lim, likePattern(q.Get("name")))
	case strings.TrimSpace(q.Get("driver")) != "":
		ts, err = a.searchByDriver(q.Get("driver"), meID(me))
	default:
		ts, err = queryTracks(a.db.read, strings.TrimPrefix(lim, " "))
	}
	if err != nil {
		internal(w, err)
		return
	}
	a.writeTracks(w, ts, nil, me)
}

// Tracciati pubblicati O corsi dai piloti cercati (solo profili pubblici, più sé stessi).
func (a *App) searchByDriver(text string, me int64) ([]*TrackRow, error) {
	return queryTracks(a.db.read, fmt.Sprintf(`WHERE t.autore_id IN (SELECT id FROM piloti WHERE nickname LIKE ?1 ESCAPE '\' AND (visibilita = 'PUBLIC' OR id = ?2))
		OR t.id IN (SELECT r.tracciato_id FROM risultati r JOIN piloti d ON d.id = r.pilota_id
			WHERE d.nickname LIKE ?1 ESCAPE '\' AND (d.visibilita = 'PUBLIC' OR d.id = ?2))
		ORDER BY t.pubblicato_il DESC LIMIT %d`, listLimit), likePattern(text), me)
}

/* ------------------------------ Risultati ----------------------------- */

// category: ?vehicleType=KART (vuoto = tutte). Valori sconosciuti → 400.
func category(r *http.Request) (string, error) {
	v := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("vehicleType")))
	if v != "" && !vehicleTypes[v] {
		return "", bad("invalid_vehicle", "Categoria di veicolo sconosciuta")
	}
	return v, nil
}

func (a *App) leaderboard(w http.ResponseWriter, r *http.Request) {
	t, me, ok := a.track(w, r)
	if !ok {
		return
	}
	cat, err := category(r)
	if err != nil {
		fail(w, err)
		return
	}
	var filter *string
	if cat != "" {
		filter = &cat
	}
	board, err := Leaderboard(a.db.read, t, filter, meID(me), 0)
	if err != nil {
		internal(w, err)
		return
	}
	writeJSON(w, 200, board)
}

func (a *App) best(w http.ResponseWriter, r *http.Request) {
	t, me, ok := a.track(w, r)
	if !ok {
		return
	}
	cat, err := category(r)
	if err != nil {
		fail(w, err)
		return
	}
	e, err := BestTime(a.db.read, t, cat, meID(me))
	if err != nil {
		internal(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{"trackId": t.UID, "best": e})
}

func (a *App) compare(w http.ResponseWriter, r *http.Request, me *Pilot) {
	t, err := trackByUID(a.db.read, r.PathValue("id"))
	if err != nil {
		internal(w, err)
		return
	}
	if t == nil {
		fail(w, errNotFound)
		return
	}
	cat, err := category(r)
	if err != nil {
		fail(w, err)
		return
	}
	c, err := Compare(a.db.read, t, cat, me.ID)
	if err != nil {
		internal(w, err)
		return
	}
	writeJSON(w, 200, c)
}

func (a *App) findDriver(w http.ResponseWriter, r *http.Request) {
	me, err := a.authOptional(r)
	if err != nil {
		internal(w, err)
		return
	}
	d, err := FindDriver(a.db.read, r.PathValue("nickname"), meID(me))
	if err != nil {
		internal(w, err)
		return
	}
	if d == nil {
		writeErr(w, http.StatusNotFound, "not_found", "Pilota non trovato o con profilo privato")
		return
	}
	writeJSON(w, 200, d)
}

// searchDrivers: GET /v1/drivers?q=an&limit=8, ricerca mentre si scrive.
func (a *App) searchDrivers(w http.ResponseWriter, r *http.Request) {
	me, err := a.authOptional(r)
	if err != nil {
		internal(w, err)
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	hits, err := SearchDrivers(a.db.read, r.URL.Query().Get("q"), meID(me), limit)
	if err != nil {
		internal(w, err)
		return
	}
	writeJSON(w, 200, hits)
}

// friendTimes: tempi degli amici sul tracciato. La lista degli amici vive
// sul telefono e arriva nella richiesta (POST perché può essere lunga).
func (a *App) friendTimes(w http.ResponseWriter, r *http.Request) {
	t, me, ok := a.track(w, r)
	if !ok {
		return
	}
	var b struct {
		UserIDs     []string `json:"userIds"`
		VehicleType *string  `json:"vehicleType"`
	}
	if err := decode(r, maxSmallBody, &b); err != nil {
		fail(w, err)
		return
	}
	if len(b.UserIDs) > MaxFriends {
		fail(w, bad("too_many_friends", fmt.Sprintf("Al massimo %d amici per richiesta", MaxFriends)))
		return
	}
	cat := ""
	if b.VehicleType != nil {
		cat = strings.ToUpper(strings.TrimSpace(*b.VehicleType))
		if cat != "" && !vehicleTypes[cat] {
			fail(w, bad("invalid_vehicle", "Categoria di veicolo sconosciuta"))
			return
		}
	}
	res, err := FriendTimes(a.db.read, t, cat, meID(me), b.UserIDs)
	if err != nil {
		internal(w, err)
		return
	}
	writeJSON(w, 200, res)
}

type lapBody struct {
	LapMs       int64   `json:"lapMs"`
	SectorsMs   []int64 `json:"sectorsMs"`
	SetAt       int64   `json:"setAt"`
	VehicleType *string `json:"vehicleType"`
	Vehicle     *string `json:"vehicle"`
}

type lapResponse struct {
	*Entry
	Improved bool `json:"improved"`
}

func (a *App) submitLap(w http.ResponseWriter, r *http.Request, me *Pilot) {
	var b lapBody
	if err := decode(r, maxSmallBody, &b); err != nil {
		fail(w, err)
		return
	}
	cat := ""
	if b.VehicleType != nil {
		cat = strings.ToUpper(strings.TrimSpace(*b.VehicleType))
		if cat != "" && !vehicleTypes[cat] {
			fail(w, bad("invalid_vehicle", "Categoria di veicolo sconosciuta"))
			return
		}
	}
	var vehicle *string
	if b.Vehicle != nil {
		if v := strings.TrimSpace(*b.Vehicle); v != "" {
			if utf8.RuneCountInString(v) > 40 {
				fail(w, bad("invalid_vehicle", "Modello del veicolo troppo lungo (massimo 40 caratteri)"))
				return
			}
			vehicle = &v
		}
	}
	now := a.now()
	if b.SetAt < 0 || b.SetAt > now+24*3600*1000 {
		fail(w, bad("invalid_lap", "Data del giro non valida"))
		return
	}
	uid := r.PathValue("id")
	var res *LapResult
	err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		t, err := trackByUID(tx, uid)
		if err != nil {
			return err
		}
		if t == nil {
			return errNotFound
		}
		ti := &TrackInfo{TotalSectors: t.TotalSectors, NeutralMask: t.NeutralMask, TimedSectors: t.TimedSectors, OverTen: t.OverTen}
		timed, err := ti.TimedFromLap(b.LapMs, b.SectorsMs)
		if err != nil {
			return bad("invalid_lap", err.Error())
		}
		res, err = SubmitLap(tx, t, me.ID, cat, vehicle, timed, b.SetAt, now)
		return err
	})
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, lapResponse{res.Entry, res.Improved})
}

func (a *App) deleteLap(w http.ResponseWriter, r *http.Request, me *Pilot) {
	lap := r.PathValue("lapId")
	err := a.db.W.Do(r.Context(), func(tx *sql.Tx) error {
		var owner int64
		err := tx.QueryRow(`SELECT pilota_id FROM risultati WHERE uid = ?`, lap).Scan(&owner)
		if errors.Is(err, sql.ErrNoRows) {
			return errNotFound
		}
		if err != nil {
			return err
		}
		if owner != me.ID {
			return errForbidden
		}
		_, err = tx.Exec(`DELETE FROM risultati WHERE uid = ?`, lap)
		return err
	})
	if err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func nowMs() int64 { return time.Now().UnixMilli() }
