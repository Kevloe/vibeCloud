-- Zugaenge fuer das Dashboard (PLAN.md Abschnitt 12).
--
-- Es gibt keine Registrierung: Ein Account entsteht nur in-game ueber '/acp create'.
-- Er haengt an der Minecraft-UUID, damit im Dashboard dieselben Rechte gelten wie im
-- Spiel - kein zweites Rechtesystem, das auseinanderlaeuft.
CREATE TABLE dashboard_accounts (
    uuid                 uuid PRIMARY KEY REFERENCES players (uuid) ON DELETE CASCADE,
    username             text        NOT NULL,
    username_lower       text        NOT NULL UNIQUE,
    password_hash        text        NOT NULL,

    -- Nach '/acp create' und '/acp changepw' gilt ein Start-Passwort. Bis es geaendert
    -- ist, ist nur die Seite "Passwort setzen" erreichbar.
    must_change_password boolean     NOT NULL DEFAULT TRUE,

    -- Wird bei jedem Rechte-Entzug und bei jedem Passwortwechsel hochgezaehlt. Ein JWT
    -- traegt die Version mit; passt sie nicht, ist es sofort ungueltig. Bewusst in der
    -- Datenbank und nicht im Speicher: Nach einem Master-Neustart wuerde ein
    -- zurueckgesetzter Zaehler alte Tokens wieder gelten lassen.
    session_version      integer     NOT NULL DEFAULT 1,

    -- Sperre nach zu vielen Fehlversuchen (PLAN.md Abschnitt 12).
    failed_logins        integer     NOT NULL DEFAULT 0,
    locked_until         timestamptz,

    -- '/acp disable' sperrt sofort, ohne den Account zu loeschen.
    disabled             boolean     NOT NULL DEFAULT FALSE,

    created_by           text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    last_login_at        timestamptz
);

-- Login sucht ueber den kleingeschriebenen Namen.
CREATE INDEX dashboard_accounts_username_idx ON dashboard_accounts (username_lower);
