-- =====================================================================
-- GPStelemetry · database della community (SQLite)
--
-- Pensato per un Raspberry Pi 4 da 1 GB condiviso con altri servizi:
--   * un solo file, nessun demone; cache limitata a pochi MB (vedi db.go);
--   * per ogni pilota, tracciato e categoria si conserva SOLO il record:
--     un giro più lento non viene mai scritto, quindi niente pulizie;
--   * un unico indice "di classifica" (tracciato, tempo) risponde a:
--     tempo migliore, posizione, distacchi, chi precede e chi segue.
--
-- Tempi in millisecondi (INTEGER), date in millisecondi epoch.
-- Lo schema è idempotente: si può rieseguire a ogni avvio.
-- =====================================================================

-- ------------------------------ PILOTI -------------------------------
CREATE TABLE IF NOT EXISTS piloti (
  id              INTEGER PRIMARY KEY,           -- chiave interna (compatta negli indici)
  uid             TEXT    NOT NULL UNIQUE,       -- userId pubblico (casuale)
  nickname        TEXT    NOT NULL,              -- chiave naturale: unica, maiuscole indifferenti
  email           TEXT,                          -- facoltativa, unica
  password_hash   TEXT,                          -- bcrypt; NULL = accesso solo con token
  nome            TEXT,                          -- nome visualizzato facoltativo
  foto_url        TEXT,                          -- indirizzo della foto profilo
  visibilita      TEXT    NOT NULL DEFAULT 'PUBLIC' CHECK (visibilita IN ('PUBLIC', 'PRIVATE')),
  notifica_record INTEGER NOT NULL DEFAULT 1,
  notifica_amici  INTEGER NOT NULL DEFAULT 0,
  creato_il       INTEGER NOT NULL
);
-- La verifica "nickname in uso?" è una ricerca su questo indice.
CREATE UNIQUE INDEX IF NOT EXISTS piloti_nickname ON piloti (nickname COLLATE NOCASE);
CREATE UNIQUE INDEX IF NOT EXISTS piloti_email ON piloti (email COLLATE NOCASE) WHERE email IS NOT NULL;

-- Accessi attivi: si salva solo l'hash SHA-256 del token.
CREATE TABLE IF NOT EXISTS sessioni (
  token_hash BLOB    PRIMARY KEY,
  pilota_id  INTEGER NOT NULL REFERENCES piloti (id) ON DELETE CASCADE,
  creato_il  INTEGER NOT NULL
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS sessioni_pilota ON sessioni (pilota_id);

-- Token per le notifiche push (predisposto).
CREATE TABLE IF NOT EXISTS dispositivi (
  push_token TEXT    PRIMARY KEY,
  pilota_id  INTEGER NOT NULL REFERENCES piloti (id) ON DELETE CASCADE,
  piattaforma TEXT   NOT NULL,
  creato_il  INTEGER NOT NULL
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS dispositivi_pilota ON dispositivi (pilota_id);

-- ----------------------------- TRACCIATI -----------------------------
CREATE TABLE IF NOT EXISTS tracciati (
  id              INTEGER PRIMARY KEY,
  uid             TEXT    NOT NULL UNIQUE,       -- id pubblico
  autore_id       INTEGER NOT NULL REFERENCES piloti (id) ON DELETE CASCADE,
  id_locale       TEXT,                          -- id del tracciato sul telefono dell'autore
  nome            TEXT    NOT NULL,
  tipo            TEXT    NOT NULL CHECK (tipo IN ('CIRCULAR', 'OPEN')),
  -- Settori del tracciato = parziali + 1, neutralizzazioni comprese.
  settori_totali  INTEGER NOT NULL CHECK (settori_totali BETWEEN 1 AND 63),
  -- Bit k acceso = settore k neutralizzato (non cronometrato).
  maschera_neutri INTEGER NOT NULL DEFAULT 0,
  -- Settori CRONOMETRATI (neutralizzazioni escluse): sono quelli salvati.
  num_settori     INTEGER NOT NULL CHECK (num_settori >= 1),
  -- Più di 10 settori cronometrati: sul server si salva solo il tempo
  -- totale (in s1) e la divisione per settori non compare. Calcolato.
  oltre_10        INTEGER GENERATED ALWAYS AS (num_settori > 10) VIRTUAL,
  nomi_settori    TEXT    NOT NULL DEFAULT '[]', -- JSON: nomi dei soli settori cronometrati
  -- Impronta di partenza, traguardo, parziali e neutralizzazioni: se cambia
  -- (linee spostate) la classifica riparte, perché i tempi non sono più confrontabili.
  impronta        TEXT    NOT NULL,
  lat             REAL    NOT NULL,              -- linea di partenza (ricerca "vicino a me")
  lon             REAL    NOT NULL,
  pubblicato_il   INTEGER NOT NULL,
  aggiornato_il   INTEGER NOT NULL,
  -- Record assoluto del tracciato (tutte le categorie), aggiornato dai trigger.
  -- Punta al risultato, che dà insieme pilota, tempo e veicolo.
  miglior_risultato_id INTEGER REFERENCES risultati (id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS tracciati_posizione ON tracciati (lat, lon);
CREATE INDEX IF NOT EXISTS tracciati_recenti   ON tracciati (pubblicato_il);
CREATE INDEX IF NOT EXISTS tracciati_autore    ON tracciati (autore_id, id_locale);

-- Geometria completa (linee e percorso, anche centinaia di KB) in una tabella
-- a parte: le righe di "tracciati" restano piccole e gli aggiornamenti del
-- record non riscrivono il percorso.
CREATE TABLE IF NOT EXISTS tracciati_geometria (
  tracciato_id INTEGER PRIMARY KEY REFERENCES tracciati (id) ON DELETE CASCADE,
  json         TEXT    NOT NULL
);

-- ----------------------------- RISULTATI -----------------------------
-- Una riga = il RECORD di un pilota su un tracciato in una categoria.
-- s1..s10 sono i settori cronometrati nell'ordine del tracciato (le
-- neutralizzazioni non ci sono). Con oltre_10 c'è solo s1 = tempo totale.
CREATE TABLE IF NOT EXISTS risultati (
  id           INTEGER PRIMARY KEY,
  uid          TEXT    NOT NULL UNIQUE,          -- lapId pubblico; cambia a ogni nuovo record
  tracciato_id INTEGER NOT NULL REFERENCES tracciati (id) ON DELETE CASCADE,
  pilota_id    INTEGER NOT NULL REFERENCES piloti (id) ON DELETE CASCADE,
  -- Categoria del veicolo ('' = non indicata): classifiche separabili.
  categoria    TEXT    NOT NULL DEFAULT '' CHECK (categoria IN
                 ('', 'CAR', 'MOTORCYCLE', 'KART', 'BICYCLE', 'MTB', 'RUNNING', 'SCOOTER', 'TRUCK', 'SKI')),
  veicolo      TEXT,                             -- modello, testo libero
  s1  INTEGER NOT NULL CHECK (s1 > 0),
  s2  INTEGER CHECK (s2 > 0),
  s3  INTEGER CHECK (s3 > 0),
  s4  INTEGER CHECK (s4 > 0),
  s5  INTEGER CHECK (s5 > 0),
  s6  INTEGER CHECK (s6 > 0),
  s7  INTEGER CHECK (s7 > 0),
  s8  INTEGER CHECK (s8 > 0),
  s9  INTEGER CHECK (s9 > 0),
  s10 INTEGER CHECK (s10 > 0),
  -- Tempo totale = somma dei settori cronometrati: calcolato, mai inviato
  -- dal telefono, quindi le neutralizzazioni non possono finirci dentro.
  -- STORED perché è la chiave dell'indice di classifica.
  tempo_totale INTEGER GENERATED ALWAYS AS (
                 s1 + ifnull(s2, 0) + ifnull(s3, 0) + ifnull(s4, 0) + ifnull(s5, 0)
                    + ifnull(s6, 0) + ifnull(s7, 0) + ifnull(s8, 0) + ifnull(s9, 0) + ifnull(s10, 0)
               ) STORED,
  impostato_il INTEGER NOT NULL,                 -- quando è stato fatto il giro
  ricevuto_il  INTEGER NOT NULL,
  UNIQUE (tracciato_id, pilota_id, categoria)
);
-- Indice di classifica: a parità di tempo vince chi l'ha fatto prima.
CREATE INDEX IF NOT EXISTS risultati_classifica ON risultati (tracciato_id, tempo_totale, impostato_il);
CREATE INDEX IF NOT EXISTS risultati_categoria  ON risultati (tracciato_id, categoria, tempo_totale, impostato_il);
CREATE INDEX IF NOT EXISTS risultati_pilota     ON risultati (pilota_id);

-- Migliori settori personali: ogni settore è il migliore di SEMPRE del
-- pilota, anche se fatto in giri diversi (giro ideale, settore viola della
-- community). Non usata per i tracciati oltre_10.
CREATE TABLE IF NOT EXISTS migliori_settori (
  tracciato_id INTEGER NOT NULL REFERENCES tracciati (id) ON DELETE CASCADE,
  pilota_id    INTEGER NOT NULL REFERENCES piloti (id) ON DELETE CASCADE,
  categoria    TEXT    NOT NULL DEFAULT '',
  s1 INTEGER, s2 INTEGER, s3 INTEGER, s4 INTEGER, s5 INTEGER,
  s6 INTEGER, s7 INTEGER, s8 INTEGER, s9 INTEGER, s10 INTEGER,
  PRIMARY KEY (tracciato_id, pilota_id, categoria)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS migliori_settori_pilota ON migliori_settori (pilota_id);

-- ------------------------------ TRIGGER ------------------------------
-- Tengono aggiornato tracciati.miglior_risultato_id. Ogni ricalcolo è una
-- sola lettura sull'indice di classifica; la riga del tracciato viene
-- riscritta solo se il record cambia davvero.
CREATE TRIGGER IF NOT EXISTS risultati_record_ins AFTER INSERT ON risultati
BEGIN
  UPDATE tracciati SET miglior_risultato_id = (
      SELECT id FROM risultati WHERE tracciato_id = NEW.tracciato_id
      ORDER BY tempo_totale, impostato_il LIMIT 1)
  WHERE id = NEW.tracciato_id AND miglior_risultato_id IS NOT (
      SELECT id FROM risultati WHERE tracciato_id = NEW.tracciato_id
      ORDER BY tempo_totale, impostato_il LIMIT 1);
END;

CREATE TRIGGER IF NOT EXISTS risultati_record_upd AFTER UPDATE OF s1, impostato_il ON risultati
BEGIN
  UPDATE tracciati SET miglior_risultato_id = (
      SELECT id FROM risultati WHERE tracciato_id = NEW.tracciato_id
      ORDER BY tempo_totale, impostato_il LIMIT 1)
  WHERE id = NEW.tracciato_id AND miglior_risultato_id IS NOT (
      SELECT id FROM risultati WHERE tracciato_id = NEW.tracciato_id
      ORDER BY tempo_totale, impostato_il LIMIT 1);
END;

-- Ritirare un record ritira anche i migliori settori collegati.
CREATE TRIGGER IF NOT EXISTS risultati_record_del AFTER DELETE ON risultati
BEGIN
  DELETE FROM migliori_settori
  WHERE tracciato_id = OLD.tracciato_id AND pilota_id = OLD.pilota_id AND categoria = OLD.categoria;
  UPDATE tracciati SET miglior_risultato_id = (
      SELECT id FROM risultati WHERE tracciato_id = OLD.tracciato_id
      ORDER BY tempo_totale, impostato_il LIMIT 1)
  WHERE id = OLD.tracciato_id;
END;

-- ------------------------------- VISTE -------------------------------
-- Tempo migliore di ogni tracciato (record assoluto), per le comparative.
CREATE VIEW IF NOT EXISTS v_tempo_migliore AS
SELECT t.id           AS tracciato_id,
       t.uid          AS tracciato_uid,
       t.nome         AS tracciato,
       r.uid          AS lap_uid,
       r.tempo_totale AS tempo_ms,
       r.impostato_il,
       r.categoria,
       r.veicolo,
       p.id           AS pilota_id,
       p.nickname,
       p.visibilita
FROM tracciati t
JOIN risultati r ON r.id = t.miglior_risultato_id
JOIN piloti    p ON p.id = r.pilota_id;

-- Record personali di ogni pilota, con il distacco dal record del tracciato.
CREATE VIEW IF NOT EXISTS v_record_personali AS
SELECT r.pilota_id,
       r.tracciato_id,
       t.uid          AS tracciato_uid,
       t.nome         AS tracciato,
       r.categoria,
       r.tempo_totale AS tempo_ms,
       r.impostato_il,
       r.tempo_totale - b.tempo_totale AS distacco_dal_record_ms
FROM risultati r
JOIN tracciati t ON t.id = r.tracciato_id
LEFT JOIN risultati b ON b.id = t.miglior_risultato_id;

-- Migliori settori della community (minimo di ogni settore tra tutti i piloti).
CREATE VIEW IF NOT EXISTS v_record_settori AS
SELECT tracciato_id, categoria,
       min(s1) AS s1, min(s2) AS s2, min(s3) AS s3, min(s4) AS s4, min(s5) AS s5,
       min(s6) AS s6, min(s7) AS s7, min(s8) AS s8, min(s9) AS s9, min(s10) AS s10
FROM migliori_settori
GROUP BY tracciato_id, categoria;
