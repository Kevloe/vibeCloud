-- M1: Was der AuthInterceptor und die Outbox dauerhaft brauchen.
-- Spieler-, Rang- und Servertabellen folgen in M2/M4.

-- Bekannte Wrapper. Das Token wird NIE im Klartext gespeichert (PLAN.md Abschnitt 13).
CREATE TABLE nodes (
    name              TEXT PRIMARY KEY,
    token_hash        TEXT        NOT NULL,
    -- Erlaubte Quell-IPs dieses Nodes. Leer = keine Einschraenkung.
    -- Ein gestohlenes Token nuetzt nichts, wenn die Verbindung von woanders kommt.
    allowed_ips       INET[]      NOT NULL DEFAULT '{}',
    max_memory_mb     BIGINT      NOT NULL DEFAULT 0,
    enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    last_seen         TIMESTAMPTZ,
    token_rotated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    failed_auths      INTEGER     NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deduplizierung der Outbox: je Herkunft die hoechste verarbeitete seq.
-- Anwendung und Cursor-Update passieren in EINER Transaktion, damit ein doppelter
-- Replay harmlos ist (PLAN.md Abschnitt 7).
CREATE TABLE event_cursors (
    origin     TEXT PRIMARY KEY,
    last_seq   BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Jede administrative Aktion mit Akteur. Auch Verstoesse gegen die Befugnis-Pruefung
-- landen hier, nicht nur im Log.
CREATE TABLE cloud_audit (
    id         BIGSERIAL PRIMARY KEY,
    actor      TEXT        NOT NULL,
    action     TEXT        NOT NULL,
    target     TEXT,
    data       JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX cloud_audit_created_at_idx ON cloud_audit (created_at DESC);
