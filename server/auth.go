package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"net/http"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

// Costo bcrypt 10: ~80-100 ms su un Pi 4, pochi KB di memoria (argon2 con
// i parametri consigliati ne userebbe decine di MB per ogni accesso).
const bcryptCost = 10

type Pilot struct {
	ID            int64
	UID           string
	Nickname      string
	Email         sql.NullString
	Name          sql.NullString
	Avatar        sql.NullString
	Visibility    string
	HasPassword   bool
	NotifyRecord  bool
	NotifyFriends bool
}

const pilotCols = `id, uid, nickname, email, nome, foto_url, visibilita, password_hash IS NOT NULL, notifica_record, notifica_amici`

func scanPilot(row *sql.Row) (*Pilot, error) {
	var p Pilot
	err := row.Scan(&p.ID, &p.UID, &p.Nickname, &p.Email, &p.Name, &p.Avatar, &p.Visibility, &p.HasPassword, &p.NotifyRecord, &p.NotifyFriends)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &p, err
}

func pilotByID(db querier, id int64) (*Pilot, error) {
	return scanPilot(db.QueryRow(`SELECT `+pilotCols+` FROM piloti WHERE id = ?`, id))
}

// account: risposta UserAccount del contratto (con token solo al login).
func (p *Pilot) account(token string) map[string]any {
	m := map[string]any{
		"userId":      p.UID,
		"nickname":    p.Nickname,
		"visibility":  p.Visibility,
		"avatarUrl":   nullable(p.Avatar),
		"email":       nullable(p.Email),
		"displayName": nullable(p.Name),
		"hasPassword": p.HasPassword,
		"settings": map[string]any{
			"notifyRecordBeaten": p.NotifyRecord,
			"notifyFriendsLaps":  p.NotifyFriends,
			"leaderboardMode":    "BEST_ONLY",
		},
	}
	if token != "" {
		m["token"] = token
	}
	return m
}

func nullable(s sql.NullString) any {
	if s.Valid {
		return s.String
	}
	return nil
}

func newToken() (plain string, hash []byte) {
	b := make([]byte, 32)
	rand.Read(b)
	plain = base64.RawURLEncoding.EncodeToString(b)
	h := sha256.Sum256([]byte(plain))
	return plain, h[:]
}

func tokenHash(plain string) []byte {
	h := sha256.Sum256([]byte(plain))
	return h[:]
}

// createSession: nuovo token per il pilota (dentro una transazione dello scrittore).
func createSession(tx *sql.Tx, pilotID, now int64) (string, error) {
	plain, hash := newToken()
	_, err := tx.Exec(`INSERT INTO sessioni (token_hash, pilota_id, creato_il) VALUES (?, ?, ?)`, hash, pilotID, now)
	return plain, err
}

func hashPassword(pw string) (string, error) {
	h, err := bcrypt.GenerateFromPassword([]byte(pw), bcryptCost)
	return string(h), err
}

func validPassword(pw string) string {
	switch {
	case len(pw) < 8:
		return "La password deve avere almeno 8 caratteri"
	case len(pw) > 72:
		return "La password può avere al massimo 72 caratteri"
	}
	return ""
}

func validEmail(e string) bool {
	at := strings.LastIndexByte(e, '@')
	return len(e) <= 254 && at > 0 && at < len(e)-3 && strings.Contains(e[at:], ".") && !strings.ContainsAny(e, " \t\r\n")
}

/* --------------------------- Middleware ------------------------------- */

type ctxKey struct{}

// authOptional: legge il token se presente (per isMe e per vedere sé stessi
// anche con il profilo privato).
func (a *App) authOptional(r *http.Request) (*Pilot, error) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		return nil, nil
	}
	tok := strings.TrimSpace(h[7:])
	if tok == "" || len(tok) > 100 {
		return nil, nil
	}
	return scanPilot(a.db.read.QueryRow(`SELECT p.id, p.uid, p.nickname, p.email, p.nome, p.foto_url, p.visibilita,
		p.password_hash IS NOT NULL, p.notifica_record, p.notifica_amici
		FROM sessioni s JOIN piloti p ON p.id = s.pilota_id WHERE s.token_hash = ?`, tokenHash(tok)))
}

// validToken: il token corrisponde a un accesso attivo (ricerca sull'indice).
func (a *App) validToken(tok string) bool {
	var one int
	return a.db.read.QueryRow(`SELECT 1 FROM sessioni WHERE token_hash = ?`, tokenHash(tok)).Scan(&one) == nil
}

func (a *App) requireAuth(next func(http.ResponseWriter, *http.Request, *Pilot)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, err := a.authOptional(r)
		if err != nil {
			internal(w, err)
			return
		}
		if p == nil {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "Accesso necessario")
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, p)), p)
	}
}
