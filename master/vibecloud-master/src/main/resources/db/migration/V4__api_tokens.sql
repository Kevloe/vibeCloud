-- Tokens fuer die REST-Schnittstelle (PLAN.md Abschnitt 12 und 13).
--
-- Gespeichert wird nur der Hash. Anders als bei Node-Tokens genuegt SHA-256: Das
-- Geheimnis ist 32 Byte aus einem Zufallsgenerator, da gibt es nichts zu erraten.
-- Argon2 waere hier nur langsam - und diese Pruefung laeuft bei jeder Anfrage.
CREATE TABLE api_tokens (
    id          text PRIMARY KEY,
    name        text        NOT NULL,
    token_hash  text        NOT NULL,
    -- Was das Token darf, z. B. {servers.read, players.write}. '*' erlaubt alles.
    scopes      text[]      NOT NULL DEFAULT '{}',
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz,
    last_used_at timestamptz
);

-- Fuer die Anzeige in 'api token list': neueste zuerst.
CREATE INDEX api_tokens_created_idx ON api_tokens (created_at DESC);
