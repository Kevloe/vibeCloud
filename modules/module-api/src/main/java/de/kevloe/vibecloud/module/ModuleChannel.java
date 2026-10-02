package de.kevloe.vibecloud.module;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Kanal zwischen dem Master-Teil eines Moduls und seinen Plattform-Bundles
 * (PLAN.md Abschnitt 10).
 *
 * <p>Absichtlich <b>keine eigenen gRPC-Services pro Modul</b>: Sonst braeuchte jedes Modul
 * eigene {@code .proto}-Dateien, Code-Generierung im Build und einen Eintrag im
 * {@code AuthInterceptor}. Stattdessen traegt die bestehende Verbindung einen generischen
 * Kanal.
 *
 * <p>Nutzlast sind Records als JSON - derselbe Serializer wie bei den Cluster-Events, also
 * kein zweites Format und kein Schema-Pflegeaufwand.
 *
 * <p><b>Server zu Server direkt geht nicht.</b> Alles laeuft ueber den Master. Damit bleibt
 * das Firewall-Modell aus Abschnitt 13 unangetastet, und es gibt genau eine Stelle, die
 * Befugnisse prueft.
 *
 * <h2>Abgrenzung zu den Events</h2>
 * Der Kanal ist fuer <b>Fragen</b>, auf die es eine Antwort braucht ("ist dieser Spieler
 * stummgeschaltet?"). Reine <b>Benachrichtigungen</b> ("Spieler wurde gebannt") laufen ueber
 * {@link ModuleContext#events()}. Nicht beides fuer dasselbe benutzen.
 */
public interface ModuleChannel {

    /**
     * Stellt eine Frage und wartet auf die Antwort.
     *
     * <p>Der Schluessel wird automatisch unter der Modul-Id gefuehrt - zwei Module koennen
     * sich also nicht in die Quere kommen.
     *
     * @param target       wohin die Frage geht
     * @param key          Schluessel, z. B. {@code is_muted}
     * @param payload      ein Record
     * @param responseType erwarteter Antworttyp
     */
    <R> CompletableFuture<R> request(Target target, String key, Object payload,
                                     Class<R> responseType);

    /**
     * Schickt etwas ohne Antwort.
     *
     * @return Anzahl der Empfaenger
     */
    int send(Target target, String key, Object payload);

    /**
     * Beantwortet Fragen zu einem Schluessel.
     *
     * @param requestType Typ der erwarteten Nutzlast
     * @param handler     liefert die Antwort; {@code null} bedeutet "keine Antwort"
     */
    <T> void respond(String key, Class<T> requestType, Function<T, Object> handler);

    /** Nimmt Nachrichten ohne Antwort an. */
    <T> void listen(String key, Class<T> requestType, java.util.function.Consumer<T> handler);

    /** Wohin eine Nachricht geht. */
    sealed interface Target {

        /** An den Master. */
        record Master() implements Target {
        }

        /** An einen bestimmten Server. */
        record Server(String name) implements Target {
        }

        /** An alle Gameserver (ohne Proxys). */
        record AllServers() implements Target {
        }

        /** An alle Proxys. */
        record AllProxies() implements Target {
        }

        static Target master() {
            return new Master();
        }

        static Target server(String name) {
            return new Server(name);
        }

        static Target allServers() {
            return new AllServers();
        }

        static Target allProxies() {
            return new AllProxies();
        }
    }
}
