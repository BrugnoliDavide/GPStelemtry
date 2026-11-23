package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"strings"
)

// MaxStoredSectors: oltre questo numero di settori cronometrati il server
// salva solo il tempo totale (in s1) e non mostra la divisione per settori.
// Sul telefono la divisione resta sempre.
const MaxStoredSectors = 10

// Massimo numero di parziali accettato (la maschera dei neutri è un intero a 64 bit).
const maxSplits = 62

// Note vocali: punti del tracciato con un testo che la voce legge al passaggio.
// Non contano nel tempo (non cambiano l'impronta né azzerano la classifica).
const (
	maxNotes    = 50
	maxNoteText = 140
)

type trackNote struct {
	Lat  float64 `json:"lat"`
	Lon  float64 `json:"lon"`
	Text string  `json:"text"`
}

type gate struct {
	Lat     float64  `json:"lat"`
	Lon     float64  `json:"lon"`
	Heading float64  `json:"heading"`
	Width   *float64 `json:"width"`
}

type splitGate struct {
	gate
	Name    string `json:"name"`
	Neutral bool   `json:"neutral"`
}

// trackGeom: i campi del tracciato (formato Json.track dell'app) che servono
// al server. Il JSON completo viene conservato così com'è.
type trackGeom struct {
	ID        string      `json:"id"`
	Name      string      `json:"name"`
	Type      string      `json:"type"`
	Tolerance float64     `json:"tolerance"`
	Start     *gate       `json:"start"`
	Finish    *gate       `json:"finish"`
	Splits    []splitGate `json:"splits"`
	Notes     []trackNote `json:"notes"`
	RemoteID  *string     `json:"remoteId"`
}

// TrackInfo: dati calcolati dalla geometria, salvati nella tabella tracciati.
type TrackInfo struct {
	Geom         trackGeom
	Raw          map[string]any // JSON completo
	TotalSectors int            // parziali + 1, neutralizzazioni comprese
	NeutralMask  int64          // bit k = settore k neutralizzato
	TimedSectors int            // settori cronometrati
	SectorNames  []string       // nomi dei settori cronometrati
	Fingerprint  string
	OverTen      bool
}

// isEntry: il parziale i apre una neutralizzazione (stessa regola di Neutral.isEntry nell'app:
// l'ultimo parziale non può essere un ingresso, perché manca l'uscita).
func isEntry(splits []splitGate, i int) bool {
	return i >= 0 && i < len(splits)-1 && splits[i].Neutral
}

func (t *TrackInfo) IsNeutral(sector int) bool { return t.NeutralMask&(1<<uint(sector)) != 0 }

// ParseTrack valida la geometria e calcola settori, maschera e impronta.
func ParseTrack(raw json.RawMessage) (*TrackInfo, error) {
	if len(raw) == 0 || len(raw) > maxTrackBytes {
		return nil, fmt.Errorf("tracciato mancante o troppo grande")
	}
	var g trackGeom
	if err := json.Unmarshal(raw, &g); err != nil {
		return nil, fmt.Errorf("tracciato non leggibile")
	}
	var m map[string]any
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := dec.Decode(&m); err != nil {
		return nil, fmt.Errorf("tracciato non leggibile")
	}
	g.Name = strings.TrimSpace(g.Name)
	switch {
	case g.Name == "" || len([]rune(g.Name)) > 80:
		return nil, fmt.Errorf("nome del tracciato mancante o troppo lungo")
	case g.Type != "CIRCULAR" && g.Type != "OPEN":
		return nil, fmt.Errorf("tipo di tracciato non valido")
	case g.Start == nil || !validGate(g.Start):
		return nil, fmt.Errorf("linea di partenza non valida")
	case g.Type == "OPEN" && (g.Finish == nil || !validGate(g.Finish)):
		return nil, fmt.Errorf("linea di traguardo non valida")
	case len(g.Splits) > maxSplits:
		return nil, fmt.Errorf("troppi parziali (massimo %d)", maxSplits)
	}
	for i := range g.Splits {
		if !validGate(&g.Splits[i].gate) {
			return nil, fmt.Errorf("parziale %d non valido", i+1)
		}
	}
	if len(g.Notes) > maxNotes {
		return nil, fmt.Errorf("troppe note (massimo %d)", maxNotes)
	}
	for i, n := range g.Notes {
		txt := strings.TrimSpace(n.Text)
		if txt == "" || len([]rune(txt)) > maxNoteText || !validGate(&gate{Lat: n.Lat, Lon: n.Lon}) || (n.Lat == 0 && n.Lon == 0) {
			return nil, fmt.Errorf("nota %d non valida (testo da 1 a %d caratteri)", i+1, maxNoteText)
		}
	}
	t := &TrackInfo{Geom: g, Raw: m, TotalSectors: len(g.Splits) + 1}
	timedNo := 0
	for k := 0; k < t.TotalSectors; k++ {
		if isEntry(g.Splits, k-1) {
			t.NeutralMask |= 1 << uint(k)
			continue
		}
		timedNo++
		name := ""
		if k < len(g.Splits) {
			name = strings.TrimSpace(g.Splits[k].Name)
			if strings.HasPrefix(name, "Parziale") || strings.HasPrefix(name, "Neutralizzazione") ||
				strings.HasPrefix(name, "Fine neutralizzazione") {
				name = ""
			}
		}
		if name == "" {
			name = fmt.Sprintf("Settore %d", timedNo)
		}
		t.SectorNames = append(t.SectorNames, name)
	}
	t.TimedSectors = timedNo
	t.OverTen = timedNo > MaxStoredSectors
	t.Fingerprint = fingerprint(&g)
	return t, nil
}

func validGate(g *gate) bool {
	return g.Lat >= -90 && g.Lat <= 90 && g.Lon >= -180 && g.Lon <= 180 &&
		!math.IsNaN(g.Lat) && !math.IsNaN(g.Lon) && (g.Width == nil || (*g.Width > 0 && *g.Width < 10000))
}

// fingerprint: impronta delle sole linee che decidono il tempo (tipo,
// tolleranza, partenza, traguardo, parziali e neutralizzazioni). Nomi e
// percorso non contano: rinominare un settore non azzera la classifica.
// Coordinate arrotondate a ~1 m, angoli al grado.
func fingerprint(g *trackGeom) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s|%.0f|", g.Type, g.Tolerance)
	w := func(x *gate) {
		if x == nil {
			b.WriteString("-|")
			return
		}
		width := 30.0
		if x.Width != nil {
			width = *x.Width
		}
		fmt.Fprintf(&b, "%.5f,%.5f,%.0f,%.0f|", x.Lat, x.Lon, math.Mod(math.Round(x.Heading)+360, 360), math.Round(width))
	}
	w(g.Start)
	if g.Type == "OPEN" {
		w(g.Finish)
	} else {
		w(nil)
	}
	for i := range g.Splits {
		w(&g.Splits[i].gate)
		if isEntry(g.Splits, i) {
			b.WriteString("N|")
		}
	}
	h := sha256.Sum256([]byte(b.String()))
	return hex.EncodeToString(h[:12])
}

// TimedFromLap: dai settori inviati dall'app (tutti, neutralizzazioni a 0)
// ai soli settori cronometrati da salvare. Verifica che il giro sia coerente
// con il tracciato: è qui che si garantisce l'imparzialità.
func (t *TrackInfo) TimedFromLap(lapMs int64, sectors []int64) ([]int64, error) {
	if lapMs <= 0 || lapMs > 24*3600*1000 {
		return nil, fmt.Errorf("tempo del giro non valido")
	}
	if len(sectors) != t.TotalSectors {
		return nil, fmt.Errorf("il giro ha %d settori, il tracciato %d", len(sectors), t.TotalSectors)
	}
	var sum int64
	timed := make([]int64, 0, t.TimedSectors)
	for k, v := range sectors {
		if t.IsNeutral(k) {
			if v != 0 {
				return nil, fmt.Errorf("il settore %d è una neutralizzazione: il suo tempo non conta", k+1)
			}
			continue
		}
		if v <= 0 {
			return nil, fmt.Errorf("tempo del settore %d non valido", k+1)
		}
		sum += v
		timed = append(timed, v)
	}
	if sum != lapMs {
		return nil, fmt.Errorf("la somma dei settori non corrisponde al tempo del giro")
	}
	if t.OverTen {
		return []int64{lapMs}, nil
	}
	return timed, nil
}

// ExpandSectors: dai settori salvati all'elenco nel formato dell'app (tutti
// i settori del tracciato, neutralizzazioni a 0). Vuoto se oltre_10.
func ExpandSectors(totalSectors int, mask int64, overTen bool, timed []int64) []int64 {
	if overTen {
		return []int64{}
	}
	out := make([]int64, 0, totalSectors)
	i := 0
	for k := 0; k < totalSectors; k++ {
		if mask&(1<<uint(k)) != 0 {
			out = append(out, 0)
			continue
		}
		if i < len(timed) {
			out = append(out, timed[i])
		} else {
			out = append(out, 0)
		}
		i++
	}
	return out
}

// distanceM: distanza tra due punti (haversine), in metri.
func distanceM(lat1, lon1, lat2, lon2 float64) float64 {
	const r = 6371000.0
	toRad := math.Pi / 180
	dLat := (lat2 - lat1) * toRad
	dLon := (lon2 - lon1) * toRad
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*toRad)*math.Cos(lat2*toRad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * r * math.Asin(math.Min(1, math.Sqrt(a)))
}
