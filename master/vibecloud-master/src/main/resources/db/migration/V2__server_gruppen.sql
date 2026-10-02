-- M2: Servergruppen, Namensvergabe, Node-Bindung statischer Server, Historie.

CREATE TABLE server_groups (
    name            TEXT PRIMARY KEY,
    platform        TEXT    NOT NULL,              -- PAPER | VELOCITY | MINESTOM
    static_group    BOOLEAN NOT NULL DEFAULT FALSE,

    min_online      INTEGER NOT NULL DEFAULT 1,
    max_online      INTEGER NOT NULL DEFAULT 1,
    max_players     INTEGER NOT NULL DEFAULT 100,
    memory_mb       INTEGER NOT NULL DEFAULT 1024,
    jvm_flags       TEXT[]  NOT NULL DEFAULT '{}',

    -- Leer = jeder Node ist erlaubt.
    allowed_nodes   TEXT[]  NOT NULL DEFAULT '{}',

    -- Ab dieser Auslastung wird ein weiterer Server gestartet (0 = aus).
    start_percent   INTEGER NOT NULL DEFAULT 0,
    -- Sekunden, die ein leerer Server ueber min_online hinaus laufen darf.
    idle_timeout    INTEGER NOT NULL DEFAULT 300,

    -- Muss %id% enthalten; validiert beim Anlegen, nicht erst beim Start.
    name_pattern    TEXT    NOT NULL DEFAULT '%group%-%id%',

    template        TEXT    NOT NULL,              -- Verzeichnis unter templates/
    mc_version      TEXT    NOT NULL DEFAULT 'latest',
    -- paper | velocity | custom:<url> | template (Jar liegt schon im Template)
    jar_source      TEXT    NOT NULL DEFAULT 'paper',

    -- M3: Einstiegsziel beim Join und Wartung je Gruppe.
    fallback        BOOLEAN NOT NULL DEFAULT FALSE,
    join_priority   INTEGER NOT NULL DEFAULT 100,
    maintenance     BOOLEAN NOT NULL DEFAULT FALSE,

    priority        INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT server_groups_name_pattern_has_id CHECK (name_pattern LIKE '%\%id\%%'),
    CONSTRAINT server_groups_online_range CHECK (max_online >= min_online)
);

-- Fortlaufende %id% je Gruppe. Eigene Tabelle, damit die Nummer einen Master-Neustart
-- ueberlebt und Namen sich nicht wiederholen, solange ein Server noch laeuft.
CREATE TABLE server_counter (
    group_name TEXT PRIMARY KEY REFERENCES server_groups (name) ON DELETE CASCADE,
    next_id    INTEGER NOT NULL DEFAULT 1
);

-- Statische Server bleiben dauerhaft auf ihrem Node: Welt, Plugin-Daten und Logs liegen
-- dort. Faellt der Node aus, bleibt der Server AUS - auf einem anderen Node kaeme er mit
-- leerer Welt hoch (PLAN.md Abschnitt 6).
CREATE TABLE static_server_bindings (
    server_name TEXT PRIMARY KEY,
    group_name  TEXT NOT NULL REFERENCES server_groups (name) ON DELETE CASCADE,
    node        TEXT NOT NULL,
    port        INTEGER NOT NULL,
    bound_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX static_server_bindings_node_idx ON static_server_bindings (node);

CREATE TABLE server_history (
    id          BIGSERIAL PRIMARY KEY,
    server_name TEXT NOT NULL,
    group_name  TEXT NOT NULL,
    node        TEXT NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    stopped_at  TIMESTAMPTZ,
    exit_code   INTEGER,
    detail      TEXT
);

CREATE INDEX server_history_group_idx ON server_history (group_name, started_at DESC);

-- Globale Schalter (Wartungsmodus, MOTD, ...). Wird ab M3 wirklich genutzt.
CREATE TABLE cloud_settings (
    key        TEXT PRIMARY KEY,
    value      JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
