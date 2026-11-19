package main

import (
	"context"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Limiter: massimo N richieste in lavorazione insieme. Chi arriva quando è
// pieno ATTENDE fino a [wait]; se non si libera un posto riceve 503 con
// Retry-After e l'app riprova più tardi. Così un picco non satura il Pi.
type Limiter struct {
	slots chan struct{}
	wait  time.Duration
}

func NewLimiter(n int, wait time.Duration) *Limiter {
	if n < 1 {
		n = 1
	}
	return &Limiter{slots: make(chan struct{}, n), wait: wait}
}

func (l *Limiter) Wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case l.slots <- struct{}{}:
		default:
			t := time.NewTimer(l.wait)
			select {
			case l.slots <- struct{}{}:
				t.Stop()
			case <-t.C:
				busy(w)
				return
			case <-r.Context().Done():
				t.Stop()
				return
			}
		}
		var once sync.Once
		release := func() { once.Do(func() { <-l.slots }) }
		defer release()
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), releaseKey{}, release)))
	})
}

type releaseKey struct{}

// releaseSlot libera il posto del Limiter prima di un'attesa che non usa CPU
// (la coda dello scrittore): chi aspetta il salvataggio a blocchi non deve
// rallentare le letture.
func releaseSlot(ctx context.Context) {
	if f, ok := ctx.Value(releaseKey{}).(func()); ok {
		f()
	}
}

func busy(w http.ResponseWriter) {
	w.Header().Set("Retry-After", strconv.Itoa(retryAfterSec))
	writeErr(w, http.StatusServiceUnavailable, "busy", "Server occupato, riprovo tra poco")
}

const retryAfterSec = 5

// RateLimit: secchiello di gettoni per indirizzo IP (protegge da abusi e
// dalla ricerca a tappeto dei nickname). Memoria limitata a maxKeys voci.
type RateLimit struct {
	mu      sync.Mutex
	perSec  float64
	burst   float64
	buckets map[string]*bucket
	maxKeys int
}

type bucket struct {
	tokens float64
	last   time.Time
}

func NewRateLimit(perSec, burst float64) *RateLimit {
	rl := &RateLimit{perSec: perSec, burst: burst, buckets: map[string]*bucket{}, maxKeys: 10000}
	go func() {
		for range time.Tick(time.Minute) {
			rl.mu.Lock()
			now := time.Now()
			for k, b := range rl.buckets {
				if now.Sub(b.last) > 10*time.Minute {
					delete(rl.buckets, k)
				}
			}
			rl.mu.Unlock()
		}
	}()
	return rl
}

func (rl *RateLimit) Allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	now := time.Now()
	b := rl.buckets[key]
	if b == nil {
		if len(rl.buckets) >= rl.maxKeys {
			return true // tabella piena: meglio servire che rifiutare tutti
		}
		b = &bucket{tokens: rl.burst, last: now}
		rl.buckets[key] = b
	}
	b.tokens = min(rl.burst, b.tokens+now.Sub(b.last).Seconds()*rl.perSec)
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// Wrap applica il limite. Chi ha un token VALIDO ha un limite suo
// (per utente): così il limite funziona anche dietro un tunnel che non
// indica l'IP vero del client (es. Tailscale Funnel, dove tutti
// sembrerebbero arrivare dallo stesso indirizzo). Un token inventato non
// apre un secchiello nuovo: si conta sull'IP.
func (rl *RateLimit) Wrap(trustProxy bool, validToken func(string) bool, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		key := "ip:" + clientIP(r, trustProxy)
		if tok := bearer(r); tok != "" && len(tok) <= 100 && validToken != nil && validToken(tok) {
			key = "u:" + tok
		}
		if !rl.Allow(key) {
			w.Header().Set("Retry-After", "2")
			writeErr(w, http.StatusTooManyRequests, "rate_limited", "Troppe richieste, riprova tra poco")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func clientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		// Cloudflare Tunnel indica il client vero in Cf-Connecting-IP.
		if cf := strings.TrimSpace(r.Header.Get("Cf-Connecting-IP")); cf != "" {
			return cf
		}
		if xf := r.Header.Get("X-Forwarded-For"); xf != "" {
			parts := strings.Split(xf, ",")
			return strings.TrimSpace(parts[len(parts)-1])
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
