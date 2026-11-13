# GPS Telemetry

Telemetria GPS per telefono: definizione di tracciati, cronometraggio automatico
con tempi parziali, controllo del margine di tolleranza ed esportazione dei dati
in **GPX 1.1**, formato generico compatibile con **Insta360 Studio** (e con
qualsiasi altro software che importi tracce GPS).

## Versioni

- **[`android/`](android/README.md)** — **app nativa Android** (consigliata) con
  companion app **Wear OS** (Samsung Galaxy Watch 4+, Pixel Watch, ...) per la
  registrazione della **frequenza cardiaca**, editor dei tracciati con punti
  trascinabili (partenza, traguardo, parziali) e registrazione in background.
- **[`editor/`](editor/index.html)** — **editor web dei tracciati** per PC e
  tablet (vedi sotto): si prepara il tracciato sulla mappa satellitare e lo si
  esporta in GPX per l'app.
- **Questo direttorio (radice)** — prototipo **web (PWA)** con le stesse logiche
  di base, utilizzabile dal browser del telefono; descritto di seguito.

## Editor web dei tracciati (`editor/`)

Pagina statica, nello stile dell'app (nero, arancione, pannelli in vetro
smerigliato, tema chiaro), pensata per preparare i tracciati con calma su
schermo grande e poi caricarli sul telefono.

- **Mappa satellitare** (Esri World Imagery) o stradale (OpenStreetMap), ricerca
  di un luogo, posizione attuale.
- **Percorso**: strumento *Percorso*, clic per aggiungere punti, clic sul
  percorso per inserirne uno, punti trascinabili, clic destro per eliminarli,
  inversione del senso.
- **Linee di cronometraggio** (partenza, traguardo, parziali): clic per
  posizionarle perpendicolari al percorso, pallino per spostarle, maniglia per
  ruotarle, cursori di angolo e larghezza, rinomina e riordino dei parziali.
- **Importa/esporta GPX** nello stesso formato dell'app (estensioni
  `gpstel`): il file esportato si importa nell'app con il tasto in alto a
  destra della scheda Cronometra e viceversa. Si può anche trascinare un GPX
  sulla pagina.
- Bozza salvata automaticamente nel browser, annulla con `Ctrl+Z`, tasti
  rapidi `D` (percorso), `S` (partenza), `F` (traguardo), `P` (parziale),
  `Esc`, `Canc` (elimina la linea selezionata).

Uso: pubblicare la cartella su un hosting statico (es. GitHub Pages:
`https://<utente>.github.io/<repo>/editor/`) oppure servirla in locale:

```bash
python3 -m http.server 8080   # poi aprire http://localhost:8080/editor/
```

---

Il resto di questo documento descrive la versione web (PWA).

## Funzionalità

### Tipi di tracciato
- **Circolare**: la partenza coincide con il traguardo (circuito).
- **Aperto**: partenza e traguardo sono punti distinti.

### Creazione dei tracciati (due modalità)
1. **Mappa + percorrenza GPS**: si posizionano sulla mappa la partenza, il traguardo
   (solo per i tracciati aperti) e gli eventuali **parziali**; quindi si preme
   *Registra percorso (GPS)* e si percorre fisicamente il tracciato: il percorso
   registrato definisce il corridoio su cui viene applicata la tolleranza.
2. **Presa diretta**: si preme *Avvia cattura*, si percorre il tracciato e si preme
   *Stop* all'arrivo. La partenza è il primo punto GPS rilevato; per i tracciati
   aperti il traguardo è l'ultimo punto. Durante la cattura è possibile
   contrassegnare i **parziali** nella posizione corrente con un pulsante dedicato.

### Parziali
I punti intermedi si definiscono in fase di creazione del tracciato (tocco sulla
mappa oppure pulsante in presa diretta) e vengono rilevati **nell'ordine di
percorrenza previsto** durante il cronometraggio.

### Margine di tolleranza
Ogni tracciato include una **tolleranza in metri** (5–100 m, predefinita 15 m) con
doppia funzione:
- è il **raggio dei cancelli** di partenza, traguardo e parziali;
- è la **semiampiezza del corridoio** attorno al percorso registrato: quando la
  posizione esce dal corridoio l'app lo segnala (banner e vibrazione) e
  contabilizza il **tempo totale trascorso fuori tolleranza** e il **numero di
  uscite**, riportati nel riepilogo della sessione.

### Cronometraggio
1. Si apre la scheda *Cronometro*, si seleziona il tracciato e si preme
   *Avvia sessione*.
2. Da quel momento l'app registra **immediatamente tutti i metadati**: posizione,
   quota, velocità, rotta, precisione GPS, **accelerometro** (~10 Hz) e
   **inclinazione** (pitch/roll).
3. Il cronometro **parte automaticamente** al passaggio dalla partenza
   (istante interpolato tra i due fix GPS a cavallo del cancello, per una
   precisione superiore alla frequenza del GPS) e **si arresta solo al
   traguardo**. Sui circuiti il traguardo viene armato soltanto dopo essersi
   allontanati dalla partenza, per evitare arresti immediati.
4. I parziali vengono registrati al passaggio dai rispettivi punti.

### Esportazione
Dalla scheda *Sessioni*:
- **GPX 1.1** — traccia completa con posizione, quota, orario e
  velocità/rotta (estensioni Garmin TrackPointExtension), più i waypoint di
  partenza/traguardo/parziali. È il formato generico da importare in
  **Insta360 Studio** (funzione di sovrimpressione dati GPS / dashboard).
- **CSV** — due file: campioni GPS e telemetria di moto (accelerometro,
  inclinazione).
- **JSON** — backup integrale della sessione.

Anche i tracciati definiti sono esportabili in GPX.

## Utilizzo

L'app è statica: non richiede backend né compilazione.

```bash
# Avvio locale di prova
python3 -m http.server 8080
# quindi aprire http://localhost:8080
```

Per l'uso sul telefono è **necessario servirla in HTTPS** (requisito dei browser per
GPS e sensori di moto): è sufficiente pubblicarla su un qualunque hosting statico
(GitHub Pages, Netlify, Cloudflare Pages, ...). Dal browser del telefono si può poi
aggiungere alla schermata home (PWA installabile, funziona anche offline per le
parti che non richiedono la mappa).

Note sui permessi:
- al primo avvio il browser chiede l'accesso alla **posizione** (scegliere
  "consenti" con precisione elevata);
- su **iOS** l'accesso ad accelerometro e giroscopio richiede un consenso
  esplicito che l'app richiede alla pressione dei pulsanti di avvio;
- durante le registrazioni l'app mantiene lo schermo acceso (Wake Lock, dove
  supportato).

## Importazione in Insta360 Studio

1. Esportare la sessione in **GPX** dalla scheda *Sessioni*.
2. In Insta360 Studio aprire il video, attivare la funzione *Dashboard /
   dati GPS* e importare il file `.gpx`.
3. Allineare la traccia al video tramite l'orario (i punti GPX contengono i
   timestamp UTC reali della registrazione).

## Architettura

| File | Ruolo |
|---|---|
| `index.html` | Struttura dell'interfaccia (viste: Tracciati, Editor, Cronometro, Sessioni) |
| `js/geo.js` | Geodesia e geometria: haversine, distanza punto-percorso, interpolazione degli attraversamenti, semplificazione Douglas-Peucker |
| `js/store.js` | Persistenza locale su IndexedDB (tracciati e sessioni) |
| `js/recorder.js` | Acquisizione GPS (`watchPosition`), accelerometro, inclinazione, Wake Lock |
| `js/timing.js` | Motore di cronometraggio: stati `armed → running → finished`, cancelli, parziali in ordine, corridoio di tolleranza |
| `js/export.js` | Generazione GPX 1.1 / CSV / JSON e download lato client |
| `js/app.js` | Logica dell'interfaccia |
| `sw.js`, `manifest.webmanifest` | PWA: cache offline e installabilità |

Dipendenza esterna: [Leaflet](https://leafletjs.com) + tile OpenStreetMap
(solo per la modalità di creazione su mappa; il resto dell'app funziona senza rete).

## Licenza

Vedere il file [LICENSE](LICENSE).

## Test

```bash
bash tests/run.sh
```

- `tests/test-engine.js`: verifica il motore di cronometraggio con dati GPS
  sintetici (tracciato aperto con parziale e fuori-tolleranza, circuito con
  partenza da fermo, passaggi ad alta velocità con cancelli attraversati tra
  due fix consecutivi).
- `tests/test-ui.js`: test end-to-end in browser headless con GPS simulato
  (creazione tracciato in presa diretta, sessione cronometrata completa,
  esportazione GPX). Richiede Node con Playwright e Chromium.
- `tests/test-editor.js`: test end-to-end dell'editor web (disegno del
  percorso, linee perpendicolari, esportazione con download, reimportazione
  identica, importazione di un GPX dell'app, bozza dopo il ricaricamento).
  Il GPX esportato viene copiato in `android/core/src/test/resources` e
  importato dal test Kotlin `WebEditorCompatTest`: garantisce che l'app
  legga i file dell'editor.
