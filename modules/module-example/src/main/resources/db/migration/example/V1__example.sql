-- Migrations eines Moduls liegen unter db/migration/<modul-id>/ - NICHT direkt unter
-- db/migration. Der ClassLoader des Moduls sieht auch die Ressourcen des Masters, und der
-- hat dort seine eigenen Migrations: Flyway faende sonst zwei Dateien mit Version 1.
--
-- Dazu gibt es eine eigene Historie-Tabelle (flyway_schema_history_example).
-- Tabellennamen beginnen mit der Modul-Id, damit zwei Module sich nicht in die Quere
-- kommen - die Datenbank ist gemeinsam.
CREATE TABLE example_greetings (
    uuid  UUID PRIMARY KEY,
    name  TEXT   NOT NULL,
    count BIGINT NOT NULL DEFAULT 0
);
