package de.kevloe.vibecloud.master.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Zusage aus PLAN.md Abschnitt 13: "Das Token ist an genau diesen Node-Namen gebunden -
 * mit dem Token von node-a kann man sich nicht als node-b anmelden."
 *
 * <p>Das hier zu testen ist wichtig, weil der Fehlerfall im Betrieb unsichtbar waere: Ein
 * falsch gebundenes Token wuerde einfach funktionieren, und niemand merkt, dass die
 * Einschraenkung fehlt.
 */
class NodeTokensTest {

    @Test
    void erzeugtJedesMalEinAnderesToken() {
        assertThat(NodeTokens.generateToken()).isNotEqualTo(NodeTokens.generateToken());
    }

    @Test
    void tokenHatAusreichendeLaenge() {
        // 32 Byte base64url ohne Padding -> 43 Zeichen
        assertThat(NodeTokens.generateToken()).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void richtigesTokenWirdAkzeptiert() {
        String token = NodeTokens.generateToken();
        String hash = NodeTokens.hash("node-a", token);

        assertThat(NodeTokens.verify("node-a", token, hash)).isTrue();
    }

    @Test
    void falschesTokenWirdAbgelehnt() {
        String hash = NodeTokens.hash("node-a", NodeTokens.generateToken());

        assertThat(NodeTokens.verify("node-a", NodeTokens.generateToken(), hash)).isFalse();
    }

    /** Der Kern: dasselbe Token, aber ein anderer Node-Name. */
    @Test
    void tokenGiltNurFuerSeinenNode() {
        String token = NodeTokens.generateToken();
        String hashForNodeA = NodeTokens.hash("node-a", token);

        assertThat(NodeTokens.verify("node-a", token, hashForNodeA)).isTrue();
        assertThat(NodeTokens.verify("node-b", token, hashForNodeA)).isFalse();
    }

    @Test
    void zweiHashesDesselbenTokensSindVerschieden() {
        String token = NodeTokens.generateToken();

        // Unterschiedliches Salt -> unterschiedlicher Hash, beide gueltig.
        String first = NodeTokens.hash("node-a", token);
        String second = NodeTokens.hash("node-a", token);

        assertThat(first).isNotEqualTo(second);
        assertThat(NodeTokens.verify("node-a", token, first)).isTrue();
        assertThat(NodeTokens.verify("node-a", token, second)).isTrue();
    }

    @Test
    void beschaedigterHashFuehrtNichtTheoretischZuEinemTreffer() {
        assertThat(NodeTokens.verify("node-a", "irgendwas", "kein-gueltiger-hash")).isFalse();
        assertThat(NodeTokens.verify("node-a", "irgendwas", "argon2id$nurzweiteile")).isFalse();
        assertThat(NodeTokens.verify("node-a", "irgendwas", "bcrypt$aaaa$bbbb")).isFalse();
        assertThat(NodeTokens.verify("node-a", "irgendwas", "argon2id$!!!$!!!")).isFalse();
    }
}
