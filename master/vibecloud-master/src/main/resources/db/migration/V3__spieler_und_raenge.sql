-- M4: Spielerdaten, Raenge und Permissions (PLAN.md Abschnitt 8 und 9).

-- ---------------------------------------------------------------- Raenge

CREATE TABLE ranks (
    id           TEXT PRIMARY KEY,
    name         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    prefix       TEXT        NOT NULL DEFAULT '',
    suffix       TEXT        NOT NULL DEFAULT '',
    color        TEXT        NOT NULL DEFAULT '<gray>',
    -- Hoeheres weight gewinnt beim Zusammenfuehren und sortiert die Tab-Liste.
    weight       INTEGER     NOT NULL DEFAULT 0,
    is_default    BOOLEAN    NOT NULL DEFAULT FALSE,
    -- MiniMessage-Template, NULL = globale Vorgabe aus der Config.
    chat_format  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Genau EIN Default-Rang. Ohne diese Bedingung bekaemen neue Spieler einen beliebigen.
CREATE UNIQUE INDEX ranks_single_default ON ranks (is_default) WHERE is_default;

-- Mehrfachvererbung als DAG. Weil ein Spieler nur einen Rang tragen kann, ist Vererbung
-- der einzige Weg, Rechte-Sets zu kombinieren (PLAN.md Abschnitt 9).
CREATE TABLE rank_inheritance (
    child_id  TEXT NOT NULL REFERENCES ranks (id) ON DELETE CASCADE,
    parent_id TEXT NOT NULL REFERENCES ranks (id) ON DELETE CASCADE,
    PRIMARY KEY (child_id, parent_id),
    -- Ein Rang kann nicht von sich selbst erben. Laengere Zyklen prueft der Code,
    -- das kann SQL nicht ausdruecken.
    CONSTRAINT rank_inheritance_no_self CHECK (child_id <> parent_id)
);

CREATE TABLE rank_permissions (
    id         BIGSERIAL PRIMARY KEY,
    rank_id    TEXT    NOT NULL REFERENCES ranks (id) ON DELETE CASCADE,
    -- Mit fuehrendem '-' ist es eine Negation, z. B. '-vibecloud.server.stop'.
    permission TEXT    NOT NULL,
    value      BOOLEAN NOT NULL DEFAULT TRUE,
    -- {"group":"bedwars"} oder {"server":"lobby-1"}; leer = ueberall.
    context    JSONB   NOT NULL DEFAULT '{}'::jsonb,
    expires_at TIMESTAMPTZ,
    granted_by TEXT,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rank_id, permission, context)
);

CREATE INDEX rank_permissions_rank_idx ON rank_permissions (rank_id);

-- ---------------------------------------------------------------- Spieler

CREATE TABLE players (
    uuid             UUID PRIMARY KEY,
    name             TEXT        NOT NULL,
    -- Fuer Suchen ohne Beachtung der Gross-/Kleinschreibung.
    name_lower       TEXT        NOT NULL,
    platform         TEXT        NOT NULL DEFAULT 'JAVA',
    -- Nur bei Bedrock gesetzt (Floodgate).
    xuid             TEXT,
    first_login      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_server      TEXT,
    playtime_seconds BIGINT      NOT NULL DEFAULT 0,
    -- Gewaehlte Sprache; NULL = noch nicht gewaehlt, dann greift die Client-Sprache.
    locale           TEXT,
    settings         JSONB       NOT NULL DEFAULT '{}'::jsonb,

    -- GENAU EIN Rang pro Spieler (PLAN.md Abschnitt 9).
    rank_id          TEXT        NOT NULL REFERENCES ranks (id),
    -- NULL = permanent.
    rank_expires_at  TIMESTAMPTZ,
    -- Rang nach Ablauf; NULL = Default-Rang.
    rank_fallback_id TEXT REFERENCES ranks (id),
    rank_granted_by  TEXT,
    rank_granted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX players_name_lower_idx ON players (name_lower);
CREATE INDEX players_rank_idx ON players (rank_id);
-- Fuer den Minuten-Scheduler, der abgelaufene Raenge zuruecksetzt.
CREATE INDEX players_rank_expiry_idx ON players (rank_expires_at)
    WHERE rank_expires_at IS NOT NULL;

CREATE TABLE player_names (
    id         BIGSERIAL PRIMARY KEY,
    uuid       UUID NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX player_names_uuid_idx ON player_names (uuid);

CREATE TABLE player_connections (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    -- IPs nur gehasht (PLAN.md Abschnitt 13).
    ip_hash         TEXT NOT NULL,
    connected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    disconnected_at TIMESTAMPTZ,
    protocol        INTEGER
);

CREATE INDEX player_connections_uuid_idx ON player_connections (uuid, connected_at DESC);

CREATE TABLE player_permissions (
    id         BIGSERIAL PRIMARY KEY,
    uuid       UUID    NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    permission TEXT    NOT NULL,
    value      BOOLEAN NOT NULL DEFAULT TRUE,
    context    JSONB   NOT NULL DEFAULT '{}'::jsonb,
    granted_by TEXT,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    UNIQUE (uuid, permission, context)
);

CREATE INDEX player_permissions_uuid_idx ON player_permissions (uuid);

-- Bei einem Einzelrang ueberschreibt jede Aenderung den vorherigen Zustand - deshalb
-- braucht es die Historie (PLAN.md Abschnitt 9).
CREATE TABLE player_rank_history (
    id         BIGSERIAL PRIMARY KEY,
    uuid       UUID NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    old_rank   TEXT,
    new_rank   TEXT NOT NULL,
    actor      TEXT NOT NULL,
    reason     TEXT,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX player_rank_history_uuid_idx ON player_rank_history (uuid, created_at DESC);

-- ---------------------------------------------------------------- Startbestand

-- Nach einer frischen Installation hat niemand Rechte. Diese zwei Raenge sind der
-- Einstieg; der erste Schritt ist dann 'rank set <name> admin' in der Konsole
-- (PLAN.md Abschnitt 9, Erst-Einrichtung).
INSERT INTO ranks (id, name, display_name, prefix, color, weight, is_default) VALUES
    ('spieler', 'spieler', 'Spieler', '', '<gray>', 0, TRUE),
    ('admin',   'admin',   'Admin',   '<red>[Admin] </red>', '<red>', 100, FALSE);

INSERT INTO rank_permissions (rank_id, permission, granted_by) VALUES
    ('spieler', 'vibecloud.language', 'MIGRATION'),
    ('admin',   'vibecloud.*',        'MIGRATION');

-- admin erbt von spieler, damit Grundrechte nicht doppelt gepflegt werden muessen.
INSERT INTO rank_inheritance (child_id, parent_id) VALUES ('admin', 'spieler');
