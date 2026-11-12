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
- **Modifica dei tracciati**: ogni linea si **sposta** trascinando il
  segnaposto centrale e si **inclina (ruota) o allarga** trascinando il
  cerchietto alla sua estremità; toccando il segnaposto si inseriscono
  **angolo e larghezza numerici** e, per i parziali, si rinomina o elimina
  la linea. Nome, tipo e tolleranza del tracciato sono modificabili.
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
```

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
