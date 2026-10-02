package de.kevloe.vibecloud.paper;

import org.bukkit.permissions.PermissibleBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die Feldsuche der Rechte-Bridge.
 *
 * <p><b>Was hier NICHT geprueft wird:</b> ob die Injektion in einen echten Bukkit-Spieler
 * funktioniert - dafuer braucht es einen laufenden Server und einen verbundenen Client.
 * {@link PermissibleBase} laesst sich hier nicht einmal erzeugen, sein Konstruktor ruft
 * {@code Bukkit.getServer()}.
 *
 * <p>Dass {@code org.bukkit.craftbukkit.entity.CraftHumanEntity} ein
 * {@code protected final PermissibleBase perm} haelt, wurde mit {@code javap} am entpackten
 * Server-Jar von Paper 26.2.build.129-stable geprueft - nicht angenommen.
 *
 * <p>Geprueft wird hier das, was sich pruefen laesst:
 * <ul>
 *   <li>Die Suche findet das Feld ueber seinen <b>Typ</b> und durchlaeuft die ganze
 *       Klassenhierarchie - das macht sie robust gegen ein Umbenennen in Paper.</li>
 *   <li>Reflection kann in dieser JVM ein {@code final}-Instanzfeld ueberhaupt
 *       beschreiben. Das ist die Annahme, auf der die Bridge steht.</li>
 * </ul>
 */
class PermissibleInjectorTest {

    /**
     * Stellvertreter fuer {@code CraftHumanEntity}.
     *
     * <p>Das Feld bleibt {@code null} - ein {@code PermissibleBase} liesse sich hier nicht
     * erzeugen. Fuer die Suche genuegt der deklarierte Typ.
     */
    @SuppressWarnings("unused")
    private static class FakeHumanEntity {
        protected final PermissibleBase perm = null;
        private final String irrelevant = "egal";
    }

    /** Stellvertreter fuer {@code CraftPlayer}: erbt das Feld. */
    private static class FakePlayer extends FakeHumanEntity {
    }

    /** Hat kein solches Feld - hier muss die Suche leer zurueckkommen. */
    @SuppressWarnings("unused")
    private static class WithoutPermissible {
        private final int zahl = 1;
    }

    /** Anderer Feldname, gleicher Typ - darf die Suche nicht stoeren. */
    @SuppressWarnings("unused")
    private static class RenamedField {
        protected final PermissibleBase permissionHolder = null;
    }

    /** Fuer die Frage, ob ein final-Instanzfeld beschreibbar ist. */
    private static class FinalFieldHolder {
        // Bewusst kein konstanter Initialisierer - sonst koennte javac den Wert inlinen
        // und der Test wuerde etwas anderes pruefen als gemeint.
        private final String value = String.valueOf("alt");
    }

    @Test
    void findetDasFeldInDerKlasseSelbst() {
        Optional<Field> field = PermissibleInjector.findPermissibleField(FakeHumanEntity.class);

        assertThat(field).isPresent();
        assertThat(field.orElseThrow().getName()).isEqualTo("perm");
    }

    /** Der eigentliche Fall: CraftPlayer erbt das Feld von CraftHumanEntity. */
    @Test
    void findetGeerbtesFeld() {
        Optional<Field> field = PermissibleInjector.findPermissibleField(FakePlayer.class);

        assertThat(field).isPresent();
        assertThat(field.orElseThrow().getDeclaringClass()).isEqualTo(FakeHumanEntity.class);
    }

    /**
     * Gesucht wird ueber den Typ, nicht ueber den Namen - ein Umbenennen in Paper bricht
     * die Bridge damit nicht.
     */
    @Test
    void findetFeldAuchUnterAnderemNamen() {
        Optional<Field> field = PermissibleInjector.findPermissibleField(RenamedField.class);

        assertThat(field).isPresent();
        assertThat(field.orElseThrow().getName()).isEqualTo("permissionHolder");
    }

    @Test
    void ignoriertFelderAnderenTyps() {
        Field field = PermissibleInjector.findPermissibleField(FakeHumanEntity.class)
                .orElseThrow();

        assertThat(field.getName()).isNotEqualTo("irrelevant");
    }

    @Test
    void liefertLeerWennEsKeinSolchesFeldGibt() {
        assertThat(PermissibleInjector.findPermissibleField(WithoutPermissible.class)).isEmpty();
        assertThat(PermissibleInjector.findPermissibleField(String.class)).isEmpty();
    }

    @Test
    void gefundenesFeldIstFreigeschaltet() {
        Field field = PermissibleInjector.findPermissibleField(FakePlayer.class).orElseThrow();

        assertThat(field.canAccess(new FakePlayer())).isTrue();
    }

    /**
     * Die Annahme, auf der die ganze Bridge steht: Ein {@code final}-Instanzfeld laesst sich
     * nach {@code setAccessible(true)} beschreiben.
     *
     * <p>Faellt dieser Test in einer kuenftigen Java-Version, funktioniert die Bridge nicht
     * mehr - dann braucht es {@code VarHandle} oder einen anderen Weg. Besser hier als im
     * Betrieb bemerkt.
     */
    @Test
    void finaleInstanzfelderSindBeschreibbar() throws ReflectiveOperationException {
        Field field = FinalFieldHolder.class.getDeclaredField("value");
        field.setAccessible(true);
        FinalFieldHolder holder = new FinalFieldHolder();

        field.set(holder, "neu");

        assertThat(field.get(holder)).isEqualTo("neu");
    }
}
