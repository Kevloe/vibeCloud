package de.kevloe.vibecloud.wrapper.config;

/**
 * Konfiguration eines Wrappers ({@code wrapper.json}).
 *
 * <p>Den passenden Inhalt gibt der Master beim Befehl {@code node add <name>} fertig aus -
 * inklusive Token und Fingerprint.
 *
 * <p>Enthaelt das Token und steht deshalb in {@code .gitignore}.
 */
public final class WrapperConfig {

    public String masterHost = "127.0.0.1";
    public int masterPort = 5000;

    /**
     * SHA-256-Fingerprint des Master-Zertifikats, Format {@code sha256:3f:a1:...}.
     *
     * <p>Wird geprueft. Ohne diese Pruefung koennte sich jemand als Master ausgeben und
     * dem Wrapper Befehle schicken (PLAN.md Abschnitt 13).
     */
    public String masterFingerprint = "";

    /** Name dieses Nodes. Das Token ist an genau diesen Namen gebunden. */
    public String node = "node-1";

    public String token = "";

    /** Wie viel RAM dieser Root fuer Gameserver bereitstellt. */
    public long maxMemoryMb = 8192;

    /**
     * Adresse, unter der die Gameserver dieses Nodes fuer die Proxys erreichbar sind.
     *
     * <p>Leer lassen heisst: Der Master nimmt die Quell-IP, mit der sich dieser Wrapper
     * verbindet. Das stimmt in den meisten Faellen. Setzen muss man es, wenn der Node
     * intern anders erreichbar ist als von aussen - etwa hinter NAT oder in einem
     * privaten Netz.
     */
    public String serverAddress = "";

    /**
     * Wie lange ein Server beim Herunterfahren des Wrappers Zeit bekommt.
     *
     * <p>Eigene Einstellung und nicht die des Masters: Hier geht der Wrapper, der Master
     * ist daran nicht beteiligt. Danach wird der Prozess beendet - ein Server, der die
     * Welt nicht in dieser Zeit speichert, haengt.
     */
    public int stopGraceSeconds = 30;

    public Firewall firewall = new Firewall();

    public Sftp sftp = new Sftp();

    public static final class Sftp {
        /**
         * SFTP-Zugang zu den Verzeichnissen der <b>statischen</b> Server dieses Nodes.
         *
         * <p>Standardmaessig aus: Es ist der einzige Port, den ein Root fuer die Cloud nach
         * aussen oeffnet, und wer ueber ihn hereinkommt, kann Plugins hochladen - also Code
         * auf diesem Node ausfuehren. Das soll eine Entscheidung sein, kein Nebeneffekt.
         */
        public boolean enabled = false;

        /** Nicht 22: Dort lauscht auf einem Root der SSH-Dienst des Betriebssystems. */
        public int port = 2222;

        public String bindAddress = "0.0.0.0";
    }

    public static final class Firewall {
        /**
         * Wenn aktiv, pflegt der Wrapper ein nftables-Set mit den Proxy-IPs, damit die
         * Gameserver-Ports nur von den Proxys erreichbar sind (PLAN.md Abschnitt 13).
         * Braucht {@code CAP_NET_ADMIN}. Umsetzung folgt in M3.
         */
        public boolean manage = false;
        public String backend = "nftables";
    }
}
