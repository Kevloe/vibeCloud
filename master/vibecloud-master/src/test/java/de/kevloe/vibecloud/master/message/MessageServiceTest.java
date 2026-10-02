package de.kevloe.vibecloud.master.message;

import de.kevloe.vibecloud.common.message.MessageBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Die Sprachdateien des Masters.
 *
 * <p>Der Anlass: Eine vorhandene {@code de.yml} wird nie ueberschrieben, damit eigene
 * Texte ein Update ueberleben. Dadurch fehlten aber alle Schluessel, die nach dem ersten
 * Start dazukamen - im Spiel stand dann der Schluesselname statt eines Satzes.
 */
class MessageServiceTest {

    @TempDir
    private Path directory;

    @Test
    @DisplayName("Beim ersten Start wird die Standardsprache abgelegt")
    void ersterStartLegtStandardspracheAb() throws IOException {
        new MessageService(directory);

        assertThat(directory.resolve("de.yml")).exists();
        assertThat(Files.readString(directory.resolve("de.yml"))).contains("tab:");
    }

    @Test
    @DisplayName("Eigene Texte bleiben unangetastet")
    void eigeneTexteBleibenUnangetastet() throws IOException {
        Files.writeString(directory.resolve("de.yml"), """
                tab:
                  header: "Mein eigener Kopf"
                  footer: "<online> da"
                """);

        MessageService service = new MessageService(directory);

        assertThat(service.bundle().raw("de", "tab.header")).isEqualTo("Mein eigener Kopf");
    }

    @Test
    @DisplayName("Fehlende Schluessel werden ergaenzt, nicht die Datei ersetzt")
    void fehlendeSchluesselWerdenErgaenzt() throws IOException {
        // Eine Datei aus einer aelteren Version: tab.* gibt es dort noch nicht.
        Files.writeString(directory.resolve("de.yml"), """
                tab:
                  header: "Mein eigener Kopf"
                """);

        MessageService service = new MessageService(directory);

        // Der eigene Text steht noch da ...
        assertThat(service.bundle().raw("de", "tab.header")).isEqualTo("Mein eigener Kopf");
        // ... und der fehlende ist dazugekommen, statt als Schluesselname zu erscheinen.
        assertThat(service.bundle().raw("de", "tab.footer")).isNotEqualTo("tab.footer");
        assertThat(service.bundle().raw("de", "error.internal")).isNotEqualTo("error.internal");
    }

    @Test
    @DisplayName("Ein zweiter Start ergaenzt nichts mehr")
    void zweiterStartErgaenztNichts() throws IOException {
        new MessageService(directory);
        String nachDemErsten = Files.readString(directory.resolve("de.yml"));

        new MessageService(directory);

        // Sonst wuechse die Datei bei jedem Start um denselben Block.
        assertThat(Files.readString(directory.resolve("de.yml"))).isEqualTo(nachDemErsten);
    }

    @Test
    @DisplayName("Ergaenzte Schluessel sind danach lesbar")
    void ergaenzteSchluesselSindLesbar() throws IOException {
        Files.writeString(directory.resolve("de.yml"), "tab:\n  header: \"Kopf\"\n");

        new MessageService(directory);

        // Die angehaengten Zeilen stehen in Punkt-Schreibweise in der Datei - der Lader
        // muss sie genauso verstehen wie die verschachtelte Form.
        try (var in = Files.newInputStream(directory.resolve("de.yml"))) {
            var flat = MessageBundle.loadYaml(in);
            assertThat(flat).containsKey("tab.header").containsKey("tab.footer");
            assertThat(flat.get("tab.header")).isEqualTo("Kopf");
        }
    }

    // ---------------------------------------------------------------- Sprachen bearbeiten

    @Test
    @DisplayName("Eine neue Sprache startet mit den Texten der Standardsprache")
    void neueSpracheStartetMitVorlage() throws IOException {
        MessageService service = new MessageService(directory);

        service.createLocale("en");

        // Leer anzulegen waere unbrauchbar: Man saehe nur Schluesselnamen und wuesste
        // nicht, was zu uebersetzen ist.
        assertThat(directory.resolve("en.yml")).exists();
        assertThat(service.entriesOfFile("en"))
                .isEqualTo(service.entriesOfFile(service.defaultLocale()));
    }

    @Test
    @DisplayName("Gespeicherte Texte sind danach wieder lesbar")
    void gespeicherteTexteSindLesbar() throws IOException {
        MessageService service = new MessageService(directory);
        service.createLocale("en");

        Map<String, String> entries = new TreeMap<>(service.entriesOfFile("en"));
        // Ein Apostroph im Text: In einfachen Anfuehrungszeichen muss er verdoppelt
        // werden, sonst endet die Zeile mitten im Satz.
        entries.put("tab.header", "It's a header <red>with tags</red>");
        service.saveFile("en", entries);
        service.reload();

        assertThat(service.entriesOfFile("en").get("tab.header"))
                .isEqualTo("It's a header <red>with tags</red>");
        assertThat(service.bundle().raw("en", "tab.header"))
                .isEqualTo("It's a header <red>with tags</red>");
    }

    @Test
    @DisplayName("Die Standardsprache laesst sich nicht loeschen")
    void standardspracheBleibt() throws IOException {
        MessageService service = new MessageService(directory);

        // Ohne sie laedt gar nichts mehr - load() wirft, und im Spiel stuenden ueberall
        // nur noch Schluesselnamen.
        assertThatThrownBy(() -> service.deleteLocale("de"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("de.yml")).exists();
    }

    @Test
    @DisplayName("Loeschen entfernt die Datei")
    void loeschenEntferntDieDatei() throws IOException {
        MessageService service = new MessageService(directory);
        service.createLocale("en");

        service.deleteLocale("en");

        assertThat(directory.resolve("en.yml")).doesNotExist();
        assertThatThrownBy(() -> service.deleteLocale("en"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("Eine Sprachkennung ist kein Dateiname")
    void sprachkennungIstKeinDateiname() throws IOException {
        MessageService service = new MessageService(directory);

        // Die Kennung wird zu einem Pfad - ohne Pruefung waere das ein Weg aus dem
        // Verzeichnis heraus.
        for (String boese : java.util.List.of("../config", "de/../../x", "..", "de.yml",
                "a b", "")) {
            assertThatThrownBy(() -> service.createLocale(boese))
                    .as("Sprachkennung %s", boese)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(MessageService.isValidLocale("pt_BR")).isTrue();
        assertThat(MessageService.isValidLocale("en")).isTrue();
    }

    @Test
    @DisplayName("Eine Sprache, die es schon gibt, wird nicht ueberschrieben")
    void vorhandeneSpracheBleibt() throws IOException {
        MessageService service = new MessageService(directory);
        service.createLocale("en");
        service.saveFile("en", Map.of("tab.header", "Mine"));

        assertThatThrownBy(() -> service.createLocale("en"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.entriesOfFile("en")).containsEntry("tab.header", "Mine");
    }

    @Test
    @DisplayName("localeFiles zeigt die Dateien, nicht die Modul-Texte")
    void localeFilesZeigtDieDateien() throws IOException {
        MessageService service = new MessageService(directory);
        service.createLocale("en");
        service.registerModuleMessages("punishment",
                Map.of("fr", Map.of("ban.screen", "Banni")));

        // fr gibt es nur im Modul - eine Datei dafuer hat der Betreiber nie angelegt,
        // und beim Speichern duerfen Modul-Texte nicht in seiner Datei landen.
        assertThat(service.localeFiles()).containsExactly("de", "en");
        assertThat(service.bundle().availableLocales()).contains("fr");
    }
}
