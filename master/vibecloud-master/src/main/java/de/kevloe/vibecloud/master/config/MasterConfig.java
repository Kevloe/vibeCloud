package de.kevloe.vibecloud.master.config;

/**
 * Konfiguration des Masters ({@code config.json}).
 *
 * <p>Keine Zugangsdaten im Code - diese Datei steht in {@code .gitignore}
 * (PLAN.md Abschnitt 13).
 */
public final class MasterConfig {

    public Grpc grpc = new Grpc();
    public Database database = new Database();
    public Http http = new Http();
    public Servers servers = new Servers();
    public Sftp sftp = new Sftp();

    public static final class Sftp {
        /**
         * SFTP-Zugang zu den <b>Templates</b> der Gruppen.
         *
         * <p>Standardmaessig aus: Wer ein Template aendert, aendert jeden Server der Gruppe
         * auf jedem Node - beim naechsten Start laeuft dort, was hochgeladen wurde. Ein
         * offener Port dafuer soll eine Entscheidung sein.
         *
         * <p>Die Verzeichnisse statischer Server liegen nicht hier, sondern auf ihren
         * Nodes; dafuer gibt es denselben Schalter in der {@code wrapper.json}.
         */
        public boolean enabled = false;

        /**
         * Nicht 2222 wie beim Wrapper: Laufen Master und Wrapper auf demselben Rechner,
         * stritten sie sich sonst um den Port.
         */
        public int port = 2223;

        public String bindAddress = "0.0.0.0";
    }

    public static final class Grpc {
        public String bindAddress = "0.0.0.0";
        public int port = 5000;
        /** Nach wie vielen Sekunden ohne Heartbeat ein Node als ausgefallen gilt. */
        public int heartbeatIntervalSeconds = 10;
        public int heartbeatTimeoutSeconds = 45;
        /** Grenze pro Nachricht; Template- und Log-Uebertragungen sind gechunkt. */
        public int maxMessageSizeMb = 4;
    }

    public static final class Database {
        public String host = "127.0.0.1";
        public int port = 5432;
        public String database = "vibecloud";
        public String user = "vibecloud";
        public String password = "devonly";
        /** HikariCP-Empfehlung: (CPU-Kerne x 2). Nicht auf 150 setzen wie im Altprojekt. */
        public int maxPoolSize = 10;
    }

    public static final class Servers {
        /**
         * Portbereich fuer Gameserver. Diese Ports duerfen nur von den Proxy-IPs erreichbar
         * sein - Gameserver laufen bei Velocity-Forwarding mit online-mode=false
         * (PLAN.md Abschnitt 13).
         */
        public int portRangeStart = 30000;
        public int portRangeEnd = 30999;

        /**
         * Oeffentlicher Port der Proxys. Spieler verbinden sich hierauf, deshalb liegt
         * er NICHT im Gameserver-Bereich - der ist per Firewall auf die Proxy-IPs
         * begrenzt und waere fuer Spieler unerreichbar (PLAN.md Abschnitt 13).
         *
         * <p>Daraus folgt: pro Node hoechstens ein Proxy, sonst waere der Port doppelt
         * belegt (PLAN.md Abschnitt 11).
         */
        public int proxyPort = 25565;

        /** Sekunden zwischen 'stop' und dem harten Beenden. */
        public int stopGraceSeconds = 30;

        /** Takt des Scheduler-Abgleichs in Sekunden. */
        public int schedulerIntervalSeconds = 3;

        /** Tage, die hochgeladene Logs auf dem Master bleiben. */
        public int logRetentionDays = 14;
    }

    public static final class Http {
        public boolean enabled = false;
        public int port = 8080;

        /**
         * Ob das Sitzungs-Cookie nur ueber HTTPS gesendet werden darf.
         *
         * <p>Im Betrieb hinter einem TLS-Proxy auf {@code true} setzen. In der
         * Entwicklung laeuft das Dashboard ueber HTTP - dort wuerde der Browser ein
         * Secure-Cookie nie mitschicken, und die Anmeldung haengt ohne Fehlermeldung.
         */
        public boolean secureCookies = false;
    }
}
