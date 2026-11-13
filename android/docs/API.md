# API della community — contratto app ↔ server

Questo documento descrive le operazioni che l'app mobile esegue verso il
server della community. Lato app sono definite dall'interfaccia
[`CommunityApi`](../app/src/main/kotlin/com/gpstelemetry/app/CommunityApi.kt);
oggi l'implementazione attiva è il **server simulato**
[`MockCommunityApi`](../app/src/main/kotlin/com/gpstelemetry/app/MockCommunityApi.kt),
che salva tutto sul telefono e applica le stesse regole qui descritte
(verificate dai test `MockCommunityApiTest`).

Per collegare il server vero: implementare `CommunityApi` con chiamate HTTP
a questi endpoint e assegnarla a `Community.api` in `GpsApp`.

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
i settori del tracciato (parziali + 1) e somma pari a `lapMs`.
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
