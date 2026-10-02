package de.kevloe.vibecloud.wrapper.firewall;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft den erzeugten nftables-Regelsatz.
 *
 * <p>Die <b>Wirkung</b> der Regeln kann hier nicht geprueft werden - die
 * Entwicklungsumgebung ist Windows. Was sich pruefen laesst, ist der Inhalt: dass die
 * Reihenfolge stimmt (erst accept fuer die Proxys, dann drop) und dass eine leere
 * Proxy-Liste alles sperrt statt alles zu oeffnen.
 *
 * <p>Diese Reihenfolge ist der Punkt, an dem ein Fehler gefaehrlich waere: Stuende das
 * {@code drop} vor dem {@code accept}, kaeme auch der Proxy nicht durch - und stuende es
 * gar nicht da, waere der Port fuer alle offen.
 */
class NftablesFirewallTest {

    private final NftablesFirewall firewall = new NftablesFirewall(true, 30000, 30999);

    @Test
    void enthaeltDieProxyAdressenImSet() {
        String ruleset = firewall.buildRuleset(Set.of("203.0.113.5"));

        assertThat(ruleset).contains("elements = { 203.0.113.5 }");
    }

    @Test
    void acceptStehtVorDrop() {
        String ruleset = firewall.buildRuleset(Set.of("203.0.113.5"));

        int accept = ruleset.indexOf("ip saddr @proxies accept");
        int drop = ruleset.indexOf("tcp dport 30000-30999 drop");

        assertThat(accept).as("accept-Regel fehlt").isNotNegative();
        assertThat(drop).as("drop-Regel fehlt").isNotNegative();
        assertThat(accept).as("accept muss vor drop stehen").isLessThan(drop);
    }

    @Test
    void sperrtDenPortbereichUeberhaupt() {
        String ruleset = firewall.buildRuleset(Set.of("203.0.113.5"));

        assertThat(ruleset).contains("tcp dport 30000-30999 drop");
    }

    /** Ohne bekannte Proxys wird gesperrt, nicht geoeffnet. */
    @Test
    void leereListeOeffnetNichts() {
        String ruleset = firewall.buildRuleset(Set.of());

        assertThat(ruleset).doesNotContain("elements =");
        assertThat(ruleset).contains("tcp dport 30000-30999 drop");
    }

    @Test
    void bestehendeVerbindungenWerdenNichtAbgeschnitten() {
        String ruleset = firewall.buildRuleset(Set.of("203.0.113.5"));

        assertThat(ruleset).contains("ct state established,related accept");
    }

    @Test
    void mehrereProxysStehenAlleImSet() {
        String ruleset = firewall.buildRuleset(Set.of("203.0.113.5", "198.51.100.9"));

        assertThat(ruleset).contains("203.0.113.5").contains("198.51.100.9");
    }

    @Test
    void regelsatzIstWiederholbarAnwendbar() {
        // Eine vollstaendige Tabellendefinition statt einzelner Regeln: Sonst wuerden sich
        // bei jedem Aufruf Regeln ansammeln.
        String ruleset = firewall.buildRuleset(Set.of("203.0.113.5"));

        assertThat(ruleset).contains("table inet vibecloud {");
        assertThat(ruleset).contains("type filter hook input priority 0");
    }
}
