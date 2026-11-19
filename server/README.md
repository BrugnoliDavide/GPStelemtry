# Server della community

Server delle classifiche di GPStelemetry. È pensato per un **Raspberry Pi 4 da
1 GB** che fa girare anche altri servizi, quindi usa poche risorse:

- **Un solo eseguibile Go** (circa 10 MB, statico, senza dipendenze) con
  **SQLite**, cioè un database in un file, senza un servizio separato.
- **Memoria**: circa 11 MB a riposo e meno di 50 MB sotto un carico pesante,
  misurati su Linux con 140 richieste al secondo.
- **Limiti di systemd**: al massimo 64 MB di memoria e il 25% di un core,
  con priorità bassa per CPU e disco.
- **Scritture non immediate**: i giri ricevuti vengono salvati a blocchi
  ogni mezzo secondo, in un'unica transazione. Nella prova 40 giri
  simultanei hanno richiesto solo 5 transazioni.
- **Server occupato**:
  - chi arriva oltre le richieste lavorate insieme **attende** fino a 3 s;
  - se la coda resta piena il server risponde `503` con `Retry-After` e
    l'app riprova più tardi.

Il contratto HTTP è [android/docs/API.md](../android/docs/API.md), con le
estensioni descritte nella sezione «Server reale» di quel documento.

## Database

Lo schema completo e commentato è in [schema.sql](schema.sql).

| Tabella | Contenuto |
|---|---|
| `piloti` | Accesso (email, password cifrata con bcrypt) e dati di base: nickname, nome visualizzato, indirizzo della foto, visibilità, notifiche. Il nickname è unico senza distinguere maiuscole e minuscole; la chiave interna è un numero. |
| `tracciati` | Nome, tipo, numero di settori, nomi dei settori, maschera delle neutralizzazioni, impronta delle linee, posizione di partenza e record assoluto (`miglior_risultato_id`). |
| `tracciati_geometria` | Geometria completa (linee e percorso), in una tabella a parte così le righe dei tracciati restano piccole. |
| `risultati` | Il **record** di un pilota su un tracciato in una categoria di veicolo: settori `s1`…`s10` e tempo totale calcolato. |
| `migliori_settori` | Il miglior tempo di sempre di ogni settore, per pilota: serve per il giro ideale e per il «settore viola» della community. |
| `sessioni`, `dispositivi` | Accessi attivi (si salva solo l'hash del token) e token per le notifiche push. |

### Regole

- **Solo i record.** Per ogni pilota, tracciato e categoria c'è una sola
  riga. Un giro più lento non viene scritto, quindi non serve mai
  cancellare nulla. I migliori settori invece si aggiornano anche con un
  giro più lento, se uno dei suoi settori è migliore.
- **Neutralizzazioni.**
  - Il tracciato sa quali settori sono neutri.
  - Il server rifiuta un giro che dà un tempo a un settore neutro, o la cui
    somma dei settori non corrisponde al tempo del giro.
  - Salva solo i settori cronometrati.
  - Il tempo totale è una colonna calcolata dal database e non arriva mai
    dal telefono.
- **Più di 10 settori cronometrati** (le neutralizzazioni non contano):
  - il flag `oltre_10` si accende da solo;
  - si salva solo il tempo totale, in `s1`, e la divisione per settori non
    compare nelle risposte del server;
  - sul telefono la divisione resta.
- **Linee spostate.**
  - Se l'autore ripubblica il tracciato con partenza, traguardo, parziali o
    neutralizzazioni diversi, l'impronta cambia e la classifica riparte da
    zero, perché i tempi vecchi non sono più confrontabili.
  - Cambiare solo i nomi o il percorso non azzera nulla.
- **Categorie di veicolo.** Il record è separato per categoria (Kart, Auto…).
  La classifica senza filtro mostra il miglior giro di ogni pilota.

### Indici e «procedure»

SQLite non ha procedure memorizzate. Al loro posto ci sono:
- **trigger**, che tengono aggiornato il record di ogni tracciato;
- **viste**: `v_tempo_migliore`, `v_record_personali`, `v_record_settori`;
- **interrogazioni con nome** in [procedures.go](procedures.go).

| Procedura | Come lavora |
|---|---|
| `NicknameAvailability` | Una ricerca sull'indice `piloti_nickname` e suggerimenti liberi. |
| `BestTime` | Il record letto direttamente da `miglior_risultato_id`; per categoria, un minimo sull'indice. |
| `Leaderboard` | Lettura ordinata dell'indice `(tracciato, tempo, data)`. |
| `Compare` | Posizione, distacco dal primo, chi precede e chi segue, percentuale di piloti battuti, record di ogni settore, giro ideale della community e personale. |
| `PersonalRecords` | Per ogni record del pilota, posizione e distacco calcolati come conteggi sull'indice: costo logaritmico. |
| `FindDriver` | Profilo pubblico dal nickname, per aggiungere un amico. I profili privati non si trovano. |
| `FriendTimes` | Tempi degli amici su un tracciato: gli id arrivano dal telefono e vengono confrontati con la classifica, con posizione e distacco da me. |
| `SubmitLap` | Salva il giro solo se migliora il record. È idempotente: lo stesso giro inviato due volte restituisce lo stesso `lapId`. |

## Compilare (sul PC)

Serve [Go](https://go.dev/dl/) 1.26 o successivo.

```bash
sh build.sh test
```

Il comando esegue i test e crea `dist/` con:
- gli eseguibili per Raspberry Pi a 64 bit (`arm64`) e a 32 bit (`armv7`);
- i file di installazione.

## Installare sul Raspberry

### Primo accesso (password dimenticata, SSH spento)

Con la scheda SD inserita nel PC, la sua partizione di avvio compare come
un disco, per esempio `D:`. Lancia da Git Bash:

```bash
sh tools/imposta-accesso.sh /d
```

- **Cosa fa lo script:**
  - chiede un nome utente e una nuova password;
  - scrive `userconf.txt`, che contiene solo l'hash della password, mai la
    password in chiaro;
  - crea il file vuoto `ssh`, che attiva SSH.
- **Al prossimo avvio:** Raspberry Pi OS (Bookworm) imposta l'utente e la
  password, poi cancella `userconf.txt`.
- **Collegamento:** dopo un paio di minuti dall'accensione,
  `ssh pi@raspberrypi.local`. Se il nome non viene trovato, cerca l'IP del
  Raspberry nella pagina del router.
- **`init=/bin/sh`:** non metterlo in `cmdline.txt`. Fa partire il
  Raspberry in una console d'emergenza senza rete né SSH, utilizzabile solo
  con monitor e tastiera.

Copia la cartella con il server (eseguibili e script di `dist/`) nella
stessa partizione, in `gpstelemetry/`. Sul Raspberry si trova in
`/boot/firmware/gpstelemetry`, ed è descritta in `LEGGIMI.txt`.

### Dati su un disco esterno

Database e foto possono stare su un disco USB, più resistente della scheda
SD alle scritture continue:

```bash
cd /boot/firmware/gpstelemetry
```
```bash
sudo sh prepare-disk.sh
```
```bash
sudo sh prepare-disk.sh /dev/sda
```
```bash
sudo sh install.sh --data /mnt/gpsdata/gpstelemetry
```

- **`prepare-disk.sh` senza argomenti:** elenca i dischi collegati. La
  scheda SD è `mmcblk0`.
- **`prepare-disk.sh /dev/sda`:** **formatta** il disco, dopo che hai
  riscritto il suo nome per conferma. Con `--keep` usa invece una
  partizione ext4 già presente, senza cancellare nulla.
- **Montaggio:** il disco viene montato sempre in `/mnt/gpsdata` e
  riconosciuto dal suo identificativo, non dalla porta USB.
- **Disco scollegato all'avvio:** il Raspberry parte lo stesso, ma il server
  **non parte**, così non scrive per errore sulla scheda.
- **`install.sh --data`:** accetta solo cartelle su un disco diverso dalla
  scheda. Se il server aveva già dei dati sulla scheda, li sposta sul disco.

### Installazione sulla scheda (senza disco esterno)

```bash
sudo sh install.sh
```

Dopo l'installazione, in entrambi i casi:
1. imposta l'indirizzo pubblico in `/etc/default/gpstelemetry` (`-public-url`);
2. riavvia il servizio:
   ```bash
   sudo systemctl restart gpstelemetry
   ```

Lo script fa questo:
- crea l'utente di sistema `gpstelemetry`;
- copia l'eseguibile in `/opt/gpstelemetry`;
- mette i dati in `/var/lib/gpstelemetry` oppure nella cartella di
  `--data`: il database `gpstelemetry.db` e le foto in `avatars/`;
- attiva il servizio, che parte da solo a ogni accensione.

Per aggiornare basta rieseguirlo: dati e opzioni restano.

| Comando | A cosa serve |
|---|---|
| `systemctl status gpstelemetry` | Stato del servizio |
| `journalctl -u gpstelemetry -f` | Log |
| `curl http://127.0.0.1:8080/v1/health` | Verifica: scritture in coda e transazioni eseguite |

**Copia del database**, quando serve: si fa senza fermare il server.

```bash
sudo -u gpstelemetry /opt/gpstelemetry/gpstelemetry-server -data /mnt/gpsdata/gpstelemetry -backup /mnt/gpsdata/copia.db
```

### Raggiungerlo da fuori senza aprire porte sul router

Il server ascolta solo su `127.0.0.1:8080`, quindi dalla rete non è
raggiungibile direttamente. Per renderlo accessibile all'app senza toccare il
router si usa un **tunnel**: un piccolo programma sul Pi apre una connessione
**in uscita** verso un servizio esterno. Le richieste dell'app arrivano
attraverso quella connessione, già in HTTPS.

Sul router non si apre nulla, e chi scansiona il tuo IP non trova porte
aperte.

#### Gratis, per iniziare: Tailscale Funnel

- **Costo:** nessuno. Usa il piano gratuito Personal di Tailscale, pensato
  per un uso non commerciale. Il limite di 6 utenti riguarda chi amministra la
  rete Tailscale, non chi usa l'app.
- **Indirizzo:** fisso e già in HTTPS, del tipo
  `https://raspberrypi.nome-rete.ts.net`. Non serve un dominio.
- **Memoria:** `tailscaled` usa circa 20–30 MB.
- **Limiti di richieste:** valgono per utente, quindi funzionano anche se
  Funnel non passa l'IP vero di chi si collega. Le richieste senza accesso
  (registrazione, verifica del nickname) condividono un unico limite; per
  la fase iniziale basta.

Passi:

1. Crea un account gratuito su [tailscale.com](https://tailscale.com),
   anche con Google o GitHub.
2. Sul Raspberry installa Tailscale e collegalo all'account. Il secondo
   comando stampa un link da aprire nel browser:
   ```bash
   curl -fsSL https://tailscale.com/install.sh | sh
   ```
   ```bash
   sudo tailscale up
   ```
3. Apri il server su Internet:
   ```bash
   sudo tailscale funnel --bg 8080
   ```
   La prima volta il comando chiede di attivare HTTPS e Funnel per la tua
   rete con un link: aprilo e conferma. Con `--bg` la configurazione resta
   anche dopo un riavvio del Pi.
4. Leggi l'indirizzo pubblico:
   ```bash
   tailscale funnel status
   ```
5. Mettilo in `/etc/default/gpstelemetry` come `-public-url`, poi riavvia
   il servizio:
   ```bash
   sudo systemctl restart gpstelemetry
   ```

Da fuori si raggiungono solo le API: ogni altro indirizzo risponde «non
trovato». Per chiudere tutto:

```bash
sudo tailscale funnel reset
```

C'è anche una prova lampo senza nessun account,
`cloudflared tunnel --url http://127.0.0.1:8080`. È gratuita, ma
l'indirizzo `*.trycloudflare.com` cambia a ogni avvio, quindi serve solo per
un test di pochi minuti.

#### Più avanti, con un dominio: Cloudflare Tunnel

- **Cosa serve:**
  - un account Cloudflare gratuito;
  - un dominio gestito da Cloudflare: si compra lì al prezzo di costo, circa
    10 € l'anno per un `.com`, oppure si sposta un dominio che hai già.
- **Vantaggi:**
  - è pensato per servizi pubblici e filtra gli attacchi prima che arrivino
    al Pi;
  - indica al server l'IP vero di chi si collega (`Cf-Connecting-IP`), così
    i limiti per indirizzo IP funzionano.
- **Memoria:** `cloudflared` usa circa 30–40 MB.
- **Costo:** il tunnel è gratuito, ma serve un dominio a pagamento: conviene
  quando la community diventa definitiva.

Passi, con la configurazione gestita dal pannello web:

1. Sul pannello Cloudflare apri **Zero Trust → Networks → Tunnels → Create
   a tunnel**, tipo *Cloudflared*, nome `gpstelemetry`.
2. Il pannello mostra un comando con un token,
   `sudo cloudflared service install <TOKEN>`: eseguilo sul Pi dopo aver
   installato `cloudflared`:
   ```bash
   sudo mkdir -p --mode=0755 /usr/share/keyrings
   curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
   echo 'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main' | sudo tee /etc/apt/sources.list.d/cloudflared.list
   sudo apt-get update && sudo apt-get install cloudflared
   ```
3. Aggiungi un **Public hostname**:
   - sottodominio `gps`, dominio il tuo;
   - percorso `^/v1/`, così passano solo le API;
   - servizio `HTTP` → `127.0.0.1:8080`.
4. In `/etc/default/gpstelemetry` imposta
   `-public-url https://gps.tuodominio.it`, lascia `-trust-proxy`, poi
   riavvia il servizio:
   ```bash
   sudo systemctl restart gpstelemetry
   ```
5. Facoltativo: limita le risorse anche a `cloudflared`, con lo stesso
   criterio del server:
   ```bash
   sudo install -D -m 0644 cloudflared-limits.conf /etc/systemd/system/cloudflared.service.d/limits.conf && sudo systemctl daemon-reload && sudo systemctl restart cloudflared
   ```

Se preferisci tenere la configurazione in un file invece che nel pannello,
c'è un esempio in [deploy/cloudflared.yml](deploy/cloudflared.yml).

#### Con le porte aperte (sconsigliato)

Per completezza:
- Caddy con certificato automatico: [deploy/Caddyfile](deploy/Caddyfile);
- nginx già presente sul Pi: `location /v1/ { proxy_pass http://127.0.0.1:8080; }`.

Entrambi richiedono l'inoltro delle porte 80 e 443 sul router.

## Opzioni

| Opzione | Predefinita | Significato |
|---|---|---|
| `-addr` | `127.0.0.1:8080` | Indirizzo di ascolto |
| `-data` | `./data` | Cartella di database e foto |
| `-public-url` | — | Indirizzo HTTPS pubblico, per i link alle foto |
| `-batch-delay` | `500ms` | Attesa per raggruppare le scritture. Più alta vuol dire meno scritture sulla scheda SD, ma risposte più lente agli invii |
| `-max-inflight` | `8` | Richieste lavorate insieme |
| `-wait` | `3s` | Attesa di un posto libero prima di rispondere `503` |
| `-queue` | `256` | Scritture in coda oltre le quali si risponde `503` |
| `-read-conns` | `2` | Connessioni di lettura al database |
| `-rate`, `-burst` | `10`, `40` | Richieste al secondo per indirizzo IP. Gli accessi con password hanno un limite a parte, più basso |
| `-trust-proxy` | no | Legge l'IP del client da `X-Forwarded-For` |
| `-backup FILE` | — | Copia del database, poi esce |

## Collegare l'app

Oggi l'app usa il server simulato (`MockCommunityApi`). Per il server vero
serve un'implementazione HTTP di `CommunityApi` che:
- chiami questi endpoint;
- con `503` o `429` attenda i secondi indicati da `Retry-After` e riprovi,
  allungando l'attesa a ogni tentativo;
- tenga in coda i giri da inviare finché il server non li accetta.
