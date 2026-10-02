package de.kevloe.vibecloud.master.http;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jedes aenderbare Feld muss auch lesbar sein.
 *
 * <p>Das Bearbeiten-Formular im Dashboard baut sich aus {@code editableFields()} und
 * fuellt die Felder mit {@code valueOf(...)}. Kommt ein Feld dazu und wird nur in einer
 * der beiden Listen eingetragen, stuende im Formular entweder ein leeres Feld oder es
 * fehlte ganz - und beides faellt erst im Betrieb auf. Dieser Test zieht es nach vorn.
 */
class EditableFieldsTest {

    private static final ServerGroup LOBBY =
            ServerGroup.defaults("lobby", ServerPlatformType.PAPER, "lobby");

    private static final RankRepository.Rank ADMIN = RankRepository.Rank.simple("admin", 100);

    @Test
    void jedesGruppenfeldHatEinenWert() {
        for (String field : ServerGroupRepository.editableFields()) {
            assertThat(ServerGroupRepository.valueOf(LOBBY, field))
                    .as("Wert von %s", field)
                    .isNotNull();
        }
    }

    @Test
    void jedesRangfeldHatEinenWert() {
        for (String field : RankRepository.editableFields()) {
            assertThat(RankRepository.valueOf(ADMIN, field))
                    .as("Wert von %s", field)
                    .isNotNull();
        }
    }

    /** {@code chat_format} ist in der Datenbank erlaubt leer - im Formular nie "null". */
    @Test
    void leeresRangfeldWirdLeererText() {
        assertThat(RankRepository.valueOf(ADMIN, "chat_format")).isEmpty();
    }

    @Test
    void unbekanntesFeldWirdAbgelehnt() {
        assertThatThrownBy(() -> ServerGroupRepository.valueOf(LOBBY, "gibtsnicht"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RankRepository.valueOf(ADMIN, "gibtsnicht"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
