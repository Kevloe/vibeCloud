-- Strafen (PLAN.md Abschnitt 10).
--
-- Eine Tabelle fuer alle Arten, statt je eine fuer Ban und Mute: Die Felder sind
-- identisch, und eine gemeinsame Historie laesst sich so in einer Abfrage zeigen.
CREATE TABLE punishment_entries (
    id            BIGSERIAL PRIMARY KEY,
    -- BAN | MUTE | KICK | WARN
    type          TEXT        NOT NULL,
    uuid          UUID        NOT NULL,
    -- Name zum Zeitpunkt der Strafe - der Spieler kann sich spaeter umbenennen.
    name          TEXT        NOT NULL,
    -- Nur gehasht (PLAN.md Abschnitt 13). NULL, wenn die IP nicht bekannt war.
    ip_hash       TEXT,
    reason        TEXT        NOT NULL,
    actor         TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL = dauerhaft. Bei KICK und WARN ohne Bedeutung.
    expires_at    TIMESTAMPTZ,

    -- Aufgehoben? Eintraege werden nie geloescht - die Historie soll vollstaendig bleiben.
    revoked_at    TIMESTAMPTZ,
    revoked_by    TEXT,
    revoke_reason TEXT,

    -- Kurze Kennung fuer den Einspruch, die ein Spieler abtippen kann.
    appeal_id     TEXT        NOT NULL UNIQUE
);

-- Die haeufigste Abfrage ueberhaupt: "hat dieser Spieler eine aktive Strafe?"
CREATE INDEX punishment_active_idx ON punishment_entries (uuid, type)
    WHERE revoked_at IS NULL;

-- Fuer IP-Bans beim Login.
CREATE INDEX punishment_ip_idx ON punishment_entries (ip_hash, type)
    WHERE revoked_at IS NULL AND ip_hash IS NOT NULL;

CREATE INDEX punishment_history_idx ON punishment_entries (uuid, created_at DESC);
