-- Zugaenge fuer SFTP zu den Verzeichnissen statischer Server.
--
-- Wie beim Dashboard haengt ein Zugang an der Minecraft-UUID: In welches Verzeichnis jemand
-- darf, entscheiden seine Rechte aus dem Spiel (vibecloud.sftp.<server>), nicht eine
-- zweite Liste hier.
--
-- Bewusst eine eigene Tabelle und nicht das Passwort des Dashboards: Das SFTP-Passwort
-- geht durch den Wrapper eines Nodes. Waere es dasselbe, haette ein uebernommener Node
-- danach die Dashboard-Zugaenge aller, die sich bei ihm angemeldet haben.
CREATE TABLE sftp_accounts (
    uuid           uuid PRIMARY KEY REFERENCES players (uuid) ON DELETE CASCADE,
    username       text        NOT NULL,
    username_lower text        NOT NULL UNIQUE,

    -- SHA-256 eines vom Master erzeugten Passworts. Fuer ein selbst gewaehltes waere das
    -- zu wenig; dieses hier ist zufaellig und lang genug, dass Durchprobieren nicht hilft
    -- (dieselbe Begruendung wie bei api_tokens).
    password_hash  text        NOT NULL,

    created_by     text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    last_login_at  timestamptz,
    last_server    text
);
