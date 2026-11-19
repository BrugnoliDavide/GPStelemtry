# API della community — contratto app ↔ server

Questo documento descrive le operazioni che l'app mobile esegue verso il
server della community. Lato app sono definite dall'interfaccia
[`CommunityApi`](../app/src/main/kotlin/com/gpstelemetry/app/CommunityApi.kt);
ci sono due implementazioni, scelte in `Community.connect`:

- [`HttpCommunityApi`](../app/src/main/kotlin/com/gpstelemetry/app/HttpCommunityApi.kt):
  il **server vero** (cartella [`server/`](../../server/README.md)),
  indirizzo in `Community.SERVER_URL`. È quella predefinita. Con server
  occupato o rete assente attende e riprova da sola; i giri non inviati
  restano in coda ([`LapQueue`](../app/src/main/kotlin/com/gpstelemetry/app/LapQueue.kt),
  WorkManager) e partono appena possibile.
- [`MockCommunityApi`](../app/src/main/kotlin/com/gpstelemetry/app/MockCommunityApi.kt):
  il **server di prova**, che salva tutto sul telefono con le stesse regole
  (Profilo → *Server di prova*).

L'account e i collegamenti (tracciati pubblicati, giri inviati) valgono per
un server alla volta: cambiando server vengono scollegati, i dati restano
sul telefono.

Test: `HttpCommunityApiTest` (attese, nuovi tentativi, errori) e
`RealServerContractTest`, l'intero contratto contro il server Go avviato
a parte (`-PgpsTestServer=http://127.0.0.1:18080/v1`).

I modelli JSON coincidono con quelli del modulo `core`
([`Community.kt`](../core/src/main/kotlin/com/gpstelemetry/core/Community.kt),
serializzazione in `CommunityJson` e `Json`).

## Convenzioni

- Base URL: `https://<server>/v1`
- Corpo e risposte in JSON (`application/json; charset=utf-8`), tempi in
  **millisecondi epoch**, distanze in **metri**, coordinate WGS84.
- Autenticazione: `Authorization: Bearer <token>` (ottenuto alla
  registrazione del nickname). Gli endpoint marcati 🔒 la richiedono.
- Errori: stato HTTP 4xx/5xx con corpo `{ "error": "codice", "message": "testo per l'utente" }`.
  L'app mostra `message`.

| Codice | Quando |
|---|---|
| `400 invalid_nickname` | formato non valido |
| `409 nickname_taken` | nickname già in uso o riservato |
| `401 unauthorized` | token mancante o non valido |
| `403 forbidden` | operazione su risorse di altri utenti |
| `404 not_found` | tracciato/giro inesistente o ritirato |

## Utenti e nickname

Regole del nickname (`Nickname.validate`): 3–20 caratteri, lettere ASCII,
cifre, `_` e `.`; non può iniziare o finire con `.`. **Unicità senza
distinzione tra maiuscole e minuscole** (`Marta` = `marta`). Alcuni nomi
sono riservati (`admin`, `root`, `moderatore`, `gpstelemetry`, `support`,
`staff`, `null`).

### `GET /nicknames/{nickname}/availability`
Verifica in tempo reale (l'app la chiama mentre si scrive, con 600 ms di
attesa dall'ultima battuta).

```json
{ "nickname": "Marta", "available": false, "reason": "Già in uso",
  "suggestions": ["Marta_rc", "Marta44", "Marta.gp"] }
```

### `POST /users`
Crea l'account con il nickname. Corpo: `{ "nickname": "davide_rc" }`.
Risposta `201`:
```json
{ "userId": "…", "nickname": "davide_rc", "token": "…",
  "visibility": "PUBLIC", "avatarUrl": null }
```
Deve ricontrollare la disponibilità (la verifica precedente non riserva il nome).

### `PATCH /users/me` 🔒
Rinomina e/o visibilità: `{ "nickname": "davide.gp" }`, `{ "visibility": "PRIVATE" }`.
Risposta: l'account aggiornato. Con profilo `PRIVATE` l'utente compare agli
altri come **"Pilota privato"** in classifiche e ricerche (ma vede sé stesso
con il proprio nome).

### `PUT /users/me/avatar` 🔒
Corpo binario `image/jpeg` (l'app invia un quadrato 512×512).
Risposta: `{ "avatarUrl": "https://…" }`.

## Tracciati

Un tracciato pubblicato contiene l'intera geometria (`Json.track`): tipo,
tolleranza, linee di partenza/traguardo/parziali (centro, orientamento,
larghezza) e percorso.

### `POST /tracks` 🔒
Pubblica un tracciato. Corpo: `{ "track": { …Json.track… } }`. Se
`track.remoteId` è un tracciato dello stesso autore, la richiesta **aggiorna**
la geometria mantenendo id e classifica (l'app la usa quando si modifica un
tracciato già pubblicato).
Risposta `201`: `PublishedTrack` (vedi sotto).

### `DELETE /tracks/{id}` 🔒
Ritira la pubblicazione (solo l'autore). Elimina anche i giri della sua
classifica. Il tracciato resta sul telefono dell'autore.

### `GET /tracks/{id}`
Dettaglio di un tracciato pubblicato (per importarlo e cronometrarsi).

### Ricerca
| Richiesta | Uso nell'app |
|---|---|
| `GET /tracks/nearby?lat=45.01&lon=9.0&radiusKm=50` | "Vicino a me": entro il raggio dalla **linea di partenza**, dal più vicino; `distanceM` valorizzato |
| `GET /tracks/search?name=lago` | "Nome": il nome contiene il testo, maiuscole indifferenti |
| `GET /tracks/search?driver=anna` | "Pilota": tracciati pubblicati **o corsi** da nickname che contengono il testo (solo profili pubblici, più sé stessi) |
| `GET /tracks` | tutti, dal più recente (se la posizione non è disponibile) |

Risposta: array di `PublishedTrack`:
```json
{
  "id": "…", "author": "anna_rc", "publishedAt": 1794486780797,
  "drivers": 3, "distanceM": 1180.5,
  "track": { "id": "…", "name": "Pista del Lago", "type": "CIRCULAR", "tolerance": 15,
             "start": { "lat": 45.0, "lon": 9.0, "heading": 90, "width": 30 },
             "finish": null, "splits": [ … ], "path": [ … ],
             "published": true, "remoteId": "…", "remoteAuthor": null },
  "topTimes": [ { "rank": 1, "driver": "anna_rc", "lapMs": 44120,
                  "sectorsMs": [14706, 14706, 14708], "setAt": 0,
                  "vehicleType": "KART", "vehicle": "Rotax Max", "lapId": "…" } ]
}
```
Ogni elemento di `splits` è `{ "lat", "lon", "heading", "width", "name" }`
più, per l'ingresso di una **neutralizzazione**, `"neutral": true`: il
settore da quel parziale al successivo (l'uscita) non conta nel tempo.

`topTimes` = prime 3 posizioni della classifica. Il server può valorizzare
`isMe` a partire dal token; l'app lo ricalcola comunque dal nickname.

## Risultati (giri)

### `POST /tracks/{id}/laps` 🔒
Invia un giro concluso. Corpo (`CommunityJson.lap`):
```json
{ "publishedTrackId": "…", "lapMs": 60500, "sectorsMs": [30000, 30500],
  "setAt": 1794486780797,
  "vehicleType": "MOTORCYCLE", "vehicle": "Honda CBR1000RR" }
```
Validazioni consigliate: `lapMs > 0`; `sectorsMs` con tanti elementi quanti
i settori del tracciato (parziali + 1) e somma pari a `lapMs`. `lapMs` è il
tempo effettivo, senza le neutralizzazioni: i loro settori valgono `0` in
`sectorsMs` (la durata reale resta solo sul telefono).
Risposta `201`: la riga di classifica del giro (`LeaderboardEntry` con
`rank` e `lapId`). L'app salva `lapId` nella sessione (`remoteLapId`).

Quando l'app invia:
- **automaticamente** a fine giro, se l'utente ha attivo "Pubblica
  automaticamente i risultati" e il tracciato è collegato (pubblicato
  dall'utente o importato da Esplora);
- **manualmente** dall'interruttore "Pubblica nella classifica" nel
  dettaglio sessione.

### `DELETE /laps/{lapId}` 🔒
Ritira un proprio giro dalla classifica.

### `GET /tracks/{id}/leaderboard`
Classifica completa: **miglior giro di ogni pilota**, dal più veloce, con
`rank`, `driver`, `lapMs`, `sectorsMs`, `setAt`, `vehicleType`, `vehicle`, `lapId`.

## Impostazioni e account (predisposti)

Nell'app l'interfaccia è pronta (Profilo → impostazioni); gli endpoint
vanno implementati sul server. L'unità di misura (metrico/imperiale) è solo
lato app: il server usa sempre unità SI (m, m/s, ms).

### `DELETE /users/me/laps` 🔒
"Elimina i miei tempi": ritira tutti i giri dell'utente da tutte le
classifiche. Risposta: `{ "deleted": 42 }`. (App: `CommunityApi.deleteAllLaps`.)

### `DELETE /users/me` 🔒
"Elimina profilo": elimina account, foto, tracciati pubblicati (con le loro
classifiche) e giri; il nickname torna disponibile. Irreversibile; l'app
chiede di riscrivere il nickname per confermare. (App: `CommunityApi.deleteAccount`.)

### `PUT /users/me/settings` 🔒
Preferenze che il server deve conoscere:
```json
{ "notifyRecordBeaten": true, "notifyFriendsLaps": false, "leaderboardMode": "BEST_ONLY" }
```
- `notifyRecordBeaten`: notifica push quando un altro pilota fa un giro più
  veloce del miglior giro dell'utente su un tracciato in cui è in classifica.
- `notifyFriendsLaps`: notifica push sui nuovi giri degli amici (richiede
  la gestione degli amici: `GET/POST/DELETE /users/me/friends`).
- `leaderboardMode`: `BEST_ONLY` (miglior giro per pilota) o `ALL_LAPS`;
  in alternativa la classifica accetta `GET /tracks/{id}/leaderboard?mode=ALL_LAPS`.

### `POST /users/me/devices` 🔒
Registrazione del token per le notifiche push (es. Firebase Cloud
Messaging): `{ "pushToken": "…", "platform": "android" }`.

## Note per il server

- **Veicolo** = categoria + modello:
  - `vehicleType`: valore fisso tra `CAR` (Auto), `MOTORCYCLE` (Moto),
    `KART`, `BICYCLE` (Bicicletta), `MTB`, `RUNNING` (Corsa a piedi),
    `SCOOTER` (Monopattino), `TRUCK` (Camion), `SKI` (Sci) — vedi `VehicleType` nel core;
    `null` se non indicato. Valori sconosciuti vanno rifiutati (`400`).
  - `vehicle`: modello in testo libero (max 40 caratteri), es. "Honda CBR1000RR".
  - Utile per filtrare le classifiche per categoria (es. solo Moto).
- Per i record di settore della community è già previsto il modello
  `CommunitySectorBest` (miglior tempo di ogni settore tra tutti i piloti).
- Le chiamate dell'app sono idempotenti dal punto di vista dell'utente:
  l'invio dei giri va protetto da duplicati (es. stesso utente, stesso
  `setAt` e `lapMs` → stesso `lapId`).

## Server reale (`server/`)

Il server in [`server/`](../../server/README.md) implementa questo contratto
con alcune estensioni. Tutte sono facoltative per l'app di oggi.

### Accesso con password

- `POST /users` accetta anche `email`, `password` (8–72 caratteri) e
  `displayName`, tutti facoltativi. Senza password l'account funziona solo
  con il token, come oggi.
- La risposta, come quella di `GET /users/me` 🔒, contiene anche `email`,
  `displayName`, `hasPassword` e `settings`.
- `POST /sessions` con `{ "login": "nickname o email", "password": "…" }`
  restituisce l'account con un nuovo `token` (`401 invalid_credentials` se
  sbagliati). `DELETE /sessions/current` 🔒 chiude l'accesso.
- `PATCH /users/me` accetta anche `displayName`, `email` e `password`.
  Per cambiare la password serve `currentPassword` se ce n'era già una, e
  gli altri accessi vengono chiusi.

### Giri

- Il server **conserva solo il record** di ogni pilota per tracciato e
  categoria (`vehicleType`), quindi `mode=ALL_LAPS` non è disponibile.
- La risposta di `POST /tracks/{id}/laps` ha in più `improved`:
  - **record migliorato** (`improved: true`): `rank` e `lapId` nuovi;
  - **giro più lento** (`improved: false`): non viene salvato, la risposta
    ha `rank: 0` e `lapId: null`;
  - **stesso giro reinviato**: stesso `lapId`.
- **Validazione dei settori**: tanti quanti i settori del tracciato,
  **0 esatto** sulle neutralizzazioni, valori positivi altrove, e somma
  pari a `lapMs`. Altrimenti `400 invalid_lap`.
- **Più di 10 settori cronometrati**: si salva solo il totale, e
  `sectorsMs` nelle classifiche è un elenco vuoto.

### Tracciati

- `PublishedTrack` ha in più `updatedAt`, `sectorNames` e `timedSectors`
  (solo i settori cronometrati), e `sectorsHidden` (vero oltre i 10
  settori).
- La ripubblicazione (`POST /tracks` con `remoteId`) restituisce
  `leaderboardReset: true` se le linee sono state spostate. In quel caso la
  classifica riparte da zero.

### Confronti

| Richiesta | Risposta |
|---|---|
| `GET /tracks/{id}/leaderboard?vehicleType=KART` | Classifica di una sola categoria |
| `GET /tracks/{id}/best?vehicleType=` | `{ "trackId", "best": LeaderboardEntry \| null }` |
| `GET /tracks/{id}/compare?vehicleType=` 🔒 | Confronto con la community (vedi sotto) |
| `GET /users/me/records` 🔒 | Record personali: `trackId`, `trackName`, `vehicleType`, `lapMs`, `setAt`, `lapId`, `rank`, `drivers`, `gapToBestMs`, `trackRecordMs` |

Campi della risposta di `/compare`:
- `me`, `best`, `gapToBestMs`;
- `ahead` e `behind`, ciascuno `{ entry, gapMs }` (`gapMs` negativo se sei
  più veloce);
- `fasterThanPct`, la percentuale di piloti più lenti;
- `sectors[]`, uno per settore cronometrato, con `index`, `number`, `name`,
  `communityBestMs`, `communityBestDriver`, `myBestMs`, `myLapMs`, `gapMs` e
  `purple`;
- `idealLapMs` e `myIdealLapMs`, i giri ideali della community e personale;
- `sectorsHidden`.

### Amici

La lista degli amici è **solo sul telefono** (`FriendsStore`, regole in
`Friends` nel core): il server non sa chi è amico di chi. L'app salva di
ogni amico lo `userId`, che resta lo stesso anche se l'amico cambia
nickname, e l'ultimo nome noto.

| Richiesta | Risposta |
|---|---|
| `GET /drivers/{nickname}` | `{ "userId", "nickname", "displayName", "avatarUrl" }`; `404` se il pilota non esiste o ha il profilo privato. Serve per aggiungere un amico |
| `POST /tracks/{id}/friends` | Tempi degli amici sul tracciato (vedi sotto) |

Corpo di `POST /tracks/{id}/friends`, con al massimo 200 amici:

```json
{ "userIds": ["…", "…"], "vehicleType": null }
```

Risposta:

```json
{ "trackId": "…", "vehicleType": null, "drivers": 12,
  "me": { …LeaderboardEntry… },
  "friends": [ { "userId": "…", "nickname": "anna.gp", "found": true,
                 "entry": { …LeaderboardEntry, rank nella classifica completa… },
                 "gapMs": -1000 } ] }
```

- `friends` elenca prima gli amici con un tempo, dal più veloce, poi quelli
  senza tempo.
- `gapMs` è il tempo dell'amico meno il mio: negativo se l'amico è più
  veloce, `null` senza accesso o se io non ho un tempo.
- `found: false` vuol dire account eliminato o profilo privato: in quel
  caso `nickname` e `entry` sono `null` e il profilo privato non viene mai
  mostrato.
- L'accesso (token) è facoltativo: senza, mancano `me` e `gapMs`.
- `nickname` è sempre il nome attuale: l'app lo usa per aggiornare la lista
  locale.

Nell'app:
- **Profilo → Gestisci amici**: aggiungi per nickname o rimuovi;
- **Esplora**: in una classifica, tieni premuto un pilota per aggiungerlo o
  toglierlo; il pulsante **Amici** mostra i loro tempi;
- **I miei tracciati**: l'azione **Tempi degli amici** compare sui
  tracciati collegati alla community.

### Server occupato

| Errore | Quando | Cosa deve fare l'app |
|---|---|---|
| `503 busy` | Il server ha troppe richieste, oppure le scritture in coda sono troppe | Attendere i secondi di `Retry-After` e riprovare, allungando l'attesa a ogni tentativo |
| `429 rate_limited` | Troppe richieste dallo stesso indirizzo IP | Come sopra |

I giri da inviare restano in coda sul telefono finché il server non li
accetta.

### Non ancora implementato

- Gli amici salvati sul server (`/users/me/friends`): per scelta la lista
  resta sul telefono, vedi «Amici».
- L'invio delle notifiche push. I token dei dispositivi vengono comunque
  salvati.
