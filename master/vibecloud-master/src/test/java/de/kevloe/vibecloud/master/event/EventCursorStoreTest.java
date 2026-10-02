package de.kevloe.vibecloud.master.event;

import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.db.Database;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Deduplizierung des Outbox-Replays gegen eine echte PostgreSQL-Instanz.
 *
 * <p>Warum gegen eine echte Datenbank und nicht mit einem Mock: Die Logik steckt im
 * {@code ON CONFLICT ... GREATEST(...)} des SQL-Statements. Ein Mock wuerde genau den Teil
 * nicht pruefen, auf den es ankommt.
 *
 * <p>Deckt den Fall aus der Resilienz-Checkliste ab: "Master zweimal hintereinander neu
 * starten, ohne dass die Outbox quittiert wurde - kein doppelter Eintrag."
 */
@Testcontainers
class EventCursorStoreTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vibecloud")
                    .withUsername("vibecloud")
                    .withPassword("test");

    private static Database database;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();

        MasterConfig.Database config = new MasterConfig.Database();
        config.host = POSTGRES.getHost();
        config.port = POSTGRES.getFirstMappedPort();
        config.database = "vibecloud";
        config.user = "vibecloud";
        config.password = "test";
        config.maxPoolSize = 4;

        database = new Database(config);
        database.migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
        POSTGRES.stop();
    }

    @Test
    void unbekannteHerkunftStartetBeiNull() {
        EventCursorStore store = new EventCursorStore(database);

        assertThat(store.lastSeq("node:frisch")).isZero();
    }

    @Test
    void cursorWandertVorwaerts() {
        EventCursorStore store = new EventCursorStore(database);

        assertThat(store.advanceTo("node:vorwaerts", 5)).isTrue();
        assertThat(store.lastSeq("node:vorwaerts")).isEqualTo(5L);

        assertThat(store.advanceTo("node:vorwaerts", 9)).isTrue();
        assertThat(store.lastSeq("node:vorwaerts")).isEqualTo(9L);
    }

    /** Ein spaeter eintreffendes aelteres Ereignis darf die Marke nicht senken. */
    @Test
    void cursorWandertNiemalsZurueck() {
        EventCursorStore store = new EventCursorStore(database);
        store.advanceTo("node:rueckwaerts", 10);

        assertThat(store.advanceTo("node:rueckwaerts", 4)).isFalse();
        assertThat(store.lastSeq("node:rueckwaerts")).isEqualTo(10L);
    }

    @Test
    void erkenntBereitsVerarbeiteteEreignisse() {
        EventCursorStore store = new EventCursorStore(database);
        store.advanceTo("node:dedup", 7);

        assertThat(store.isDuplicate("node:dedup", 3)).isTrue();
        assertThat(store.isDuplicate("node:dedup", 7)).isTrue();
        assertThat(store.isDuplicate("node:dedup", 8)).isFalse();
        // seq 0 heisst "nicht dedupliziert" (Heartbeats) und darf nie als Duplikat gelten.
        assertThat(store.isDuplicate("node:dedup", 0)).isFalse();
    }

    /**
     * Der eigentliche Resilienzfall: Ein zweiter Master-Prozess (neuer Store, leerer Cache)
     * darf keinen Eintrag doppelt verarbeiten, den der erste schon quittiert hat.
     */
    @Test
    void neuerProzessKenntDenStandAusDerDatenbank() {
        new EventCursorStore(database).advanceTo("node:neustart", 42);

        EventCursorStore nachNeustart = new EventCursorStore(database);

        assertThat(nachNeustart.lastSeq("node:neustart")).isEqualTo(42L);
        assertThat(nachNeustart.isDuplicate("node:neustart", 42)).isTrue();
        assertThat(nachNeustart.advanceTo("node:neustart", 42)).isFalse();
    }

    @Test
    void herkuenfteSindVoneinanderUnabhaengig() {
        EventCursorStore store = new EventCursorStore(database);
        store.advanceTo("node:eins", 100);
        store.advanceTo("node:zwei", 3);

        assertThat(store.lastSeq("node:eins")).isEqualTo(100L);
        assertThat(store.lastSeq("node:zwei")).isEqualTo(3L);
    }
}
