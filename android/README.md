# GPS Telemetry — App Android + Wear OS

Applicazione nativa Android di **telemetria GPS** con companion app **Wear OS**
(Samsung Galaxy Watch 4 e successivi, Pixel Watch e ogni orologio Wear OS 3+)
per la registrazione della **frequenza cardiaca** durante l'attività.

## Moduli

| Modulo | Contenuto |
|---|---|
| `core` | Libreria Kotlin pura condivisa: modelli, geodesia, **motore di cronometraggio**, GPX, JSON. Compilabile e testata su JVM (`./gradlew :core:test`). |
| `app` | App telefono (minSdk 26): mappe OSM (osmdroid), editor tracciati, cronometro con servizio in foreground, sessioni ed esportazioni. |
| `wear` | App orologio (minSdk 30 / Wear OS 3): misura HR con Health Services e la invia al telefono via Data Layer. |

## Funzionalità (app telefono)

- **Tracciati circolari** (partenza = traguardo) e **aperti** (punti distinti).
- **Cancelli a LINEA**: partenza, traguardo e parziali (settori) sono vere
  **linee di cronometraggio** sul terreno — non zone circolari — definite da
  centro, **orientamento (angolo)** e **larghezza**. Alla creazione vengono
  orientate automaticamente perpendicolari al percorso, quando disponibile.
- **Creazione** in due modalità: selezione delle linee sulla mappa +
  percorrenza GPS del percorso, oppure **presa diretta** (partenza = primo
  fix, traguardo = ultimo fix, parziali contrassegnabili al volo; tutte le
  linee perpendicolari alla direzione di moto).
- **Editor dei tracciati** a mappa intera con **vista satellitare**
  predefinita (immagini Esri World Imagery, per vedere bordi della pista,
  cordoli e vie di fuga) e pulsante per passare alla mappa stradale (scura
  nel tema scuro); la scelta è ricordata. Tasto indietro (avvisa se ci sono
  modifiche non salvate). Le linee sono
  disegnate con la loro vera lunghezza e orientamento — a scacchi per
  partenza/traguardo, arancioni per i parziali (P1, P2, …) — e restano
  visibili come linee anche a zoom bassi. Ogni linea si **sposta**
  trascinando il pallino centrale; toccandola si seleziona e si **ruota**
  trascinando la maniglia bianca, oppure con i cursori di **angolo** e
  **larghezza** e il tasto "⟂ Al percorso"; i parziali si rinominano o
  eliminano dallo stesso pannello. Nome, tipo e tolleranza sono in alto.
- **Tolleranza/margine** per tracciato (5–100 m): semiampiezza del corridoio
  attorno al percorso; tempo e numero di uscite fuori tolleranza sono
  contabilizzati.
- **Cronometraggio automatico**: la telemetria (GPS ~2 Hz, accelerometro
  ~10 Hz, inclinazione pitch/roll, frequenza cardiaca dall'orologio) parte
  all'avvio della sessione; il tempo parte all'**attraversamento della linea
  di partenza** e si arresta su quella del traguardo, con parziali intermedi.
  Il rilevamento è un'intersezione segmento-segmento tra la traiettoria GPS
  e la linea, con istante interpolato: robusto anche ad alta velocità, quando
  i fix sono molto distanziati.
- **Navigazione a schede** (barra in basso): **Cronometra** (tracciati
  personali, editor, cronometro), **Esplora** (tracciati pubblicati dalla
  community: ricerca *vicino a me*, per *nome* o per *pilota*, podio e
  classifica completa, "Aggiungi ai miei tracciati" per cronometrarsi sulle
  stesse linee) e **Sessioni** (storico dei giri, record in viola).
- **Profilo** (avatar in alto nella scheda Cronometra): foto profilo,
  **nickname verificato sul server** mentre lo scrivi (con suggerimenti se
  è già preso), profilo pubblico/privato, invio automatico dei risultati,
  tema scuro/chiaro/sistema e spazio per le impostazioni future.
- **Tutorial** al primo avvio (rivedibile da Profilo → Aiuto): funzioni
  base, prova del tasto play su un tracciato di prova (non salvato), invito
  a non toccare il telefono durante la guida e **avvertenze d'uso** da
  accettare con "HO CAPITO" (data di accettazione salvata). I permessi
  vengono chiesti dopo il tutorial.
- **Impostazioni** (nel profilo): **unità di misura** metriche (km/h, m,
  km) o imperiali (mph, ft, mi), applicate in tutta l'app. Predisposte
  (interfaccia e preferenze pronte, attive con il server; contratto in
  `docs/API.md`): notifiche quando qualcuno batte un tuo record e sui giri
  degli amici, classifiche con solo il giro migliore o tutti i giri,
  "Elimina i miei tempi" ed "Elimina profilo" (conferma riscrivendo il
  nickname).
- **Resoconto del giro** (fine giro, dettaglio sessione, immagine):
  dislivello positivo/negativo, velocità massima e media, accelerazione
  massima (g, dal GPS), inclinazione massima (rispetto alla posizione di
  riposo del telefono) e avviso delle uscite dal tracciato.
- **Pubblicazione**: ogni tracciato si pubblica o ritira dal suo menu; i
  giri si pubblicano automaticamente (se attivo) o dall'interruttore nel
  dettaglio sessione, e si possono ritirare.
- **Dettaglio sessione**: tempo e settori colorati, **veicolo** usato
  (testo libero con suggerimenti; l'ultimo usato è proposto per le nuove
  sessioni), pubblicazione in classifica, immagine, esportazioni.
- **Server**: per ora è un **server simulato** sul telefono
  (`MockCommunityApi`, segnalato in app come "Server di prova"). Il
  contratto per il server vero è in [`docs/API.md`](docs/API.md).
- **START TRACKING**: la schermata cronometro si apre in attesa; il tasto
  arancione con il simbolo play avvia la registrazione (telemetria + armamento
  della linea di partenza). Dall'elenco, il tasto play di ogni scheda apre
  direttamente il cronometro di quel tracciato.
- **Dashboard orizzontale**: ruotando il telefono (o con il tasto dedicato)
  il cronometro diventa una dashboard a tutto schermo con il **tempo
  gigante** e, sopra, una **barra divisa in settori** (l'intera barra se il
  tracciato non ha parziali). Ogni settore concluso si colora rispetto allo
  storico del tracciato: **viola** = miglior tempo di sempre, **verde** =
  meglio della media, **rosso** = peggio della media; lo stesso vale per il
  tempo finale del giro. Il settore in corso mostra l'avanzamento rispetto
  al suo tempo medio.
- **Stile**: nero vero ad alto contrasto con accento arancione e schede in
  vetro smerigliato (traslucide su uno sfondo con bagliori sfumati). Tema
  **chiaro** già predisposto: si alterna scuro → chiaro → sistema con il
  tasto in alto nell'elenco tracciati (colori in `res/values` e
  `res/values-night`).
- **Schede tracciato**: a sinistra la forma stilizzata del tracciato, al
  centro nome, tipologie e record, a destra il tasto play.
- **Immagine da condividere**: a fine giro (o dalla scheda Sessioni) genera
  un'immagine 1080×1350 con nome del tracciato, traiettoria stilizzata
  colorata per settore, tempo del giro e confronto col record, tempi dei
  settori e telemetria; si condivide con il foglio di Android o si salva in
  PNG.
- Registrazione in **servizio foreground** con wake lock: continua a schermo
  spento.
- **Esportazioni**: GPX 1.1 (con `gpxtpx:hr`, `gpxtpx:speed`, `gpxtpx:course`
  nelle estensioni Garmin — formato generico importabile in **Insta360
  Studio**), CSV (GPS / moto / cuore) e JSON integrale.

## Frequenza cardiaca dallo smartwatch

1. Installare il modulo `wear` sull'orologio (stesso `applicationId` e stessa
   firma dell'app telefono: **requisito del Data Layer**).
2. Aprire l'app sull'orologio e premere **Avvia cuore** (richiede il permesso
   sensori al primo avvio).
3. I campioni (1 Hz) arrivano al telefono sul percorso `/gpstelemetry/hr` e
   vengono associati alla sessione in corso; compaiono in tempo reale nella
   schermata cronometro e nel GPX esportato (`<gpxtpx:hr>`).

Nota: su orologi Samsung il sensore è esposto tramite Health Services, lo
stesso usato dall'app; non serve alcuna integrazione Samsung specifica.

## Compilazione

Richiede Android Studio (o SDK Android + JDK 17+):

```bash
cd android
./gradlew :app:assembleDebug     # APK telefono
./gradlew :wear:assembleDebug    # APK orologio
./gradlew :core:test             # test del motore (eseguibili ovunque, senza SDK)
./gradlew :app:testDebugUnitTest # rendering delle schermate su JVM (Robolectric)
```

`:app:testDebugUnitTest` disegna le schermate principali (elenco in tema
scuro e chiaro, cronometro verticale/orizzontale, immagine da condividere,
sessioni) con dati di prova e le salva in `app/build/screenshots/`: utile
per controllare l'aspetto senza emulatore. La prima esecuzione scarica il
runtime Android di Robolectric.

In assenza dell'SDK Android (variabile `ANDROID_HOME`/`local.properties`),
il progetto include solo `:core`, così i test di logica restano eseguibili
in qualunque ambiente.

Installazione: `./gradlew :app:installDebug` con il telefono collegato in
debug USB; per l'orologio, debug via Wi-Fi/Bluetooth e
`./gradlew :wear:installDebug`. Le due app devono essere firmate con la
stessa chiave (in debug lo sono automaticamente sulla stessa macchina).

## Permessi richiesti

- Telefono: posizione precisa (GPS), notifiche (Android 13+), servizio in
  foreground di tipo localizzazione.
- Orologio: sensori corporei (frequenza cardiaca).

## Importazione in Insta360 Studio

Esportare la sessione in GPX dalla schermata *Sessioni* e importare il file
nella funzione dashboard/dati GPS di Insta360 Studio; i punti contengono i
timestamp UTC reali per l'allineamento con il video.
