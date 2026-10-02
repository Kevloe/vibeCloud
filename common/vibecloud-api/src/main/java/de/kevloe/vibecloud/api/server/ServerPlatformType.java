package de.kevloe.vibecloud.api.server;

/** Plattform eines Servers. Bestimmt Startargumente und das globale Template. */
public enum ServerPlatformType {

    /** Paper-Gameserver. Startet mit {@code --port}, nutzt {@code global/server}. */
    PAPER("global/server", true),

    /** Velocity-Proxy. Port steht in der {@code velocity.toml}, nutzt {@code global/proxy}. */
    VELOCITY("global/proxy", false),

    /** Minestom-Server mit eigenem {@code main()}. Jar kommt aus dem Template. */
    MINESTOM("global/server", true);

    private final String globalTemplate;
    private final boolean portAsArgument;

    ServerPlatformType(String globalTemplate, boolean portAsArgument) {
        this.globalTemplate = globalTemplate;
        this.portAsArgument = portAsArgument;
    }

    /** Welches globale Template vor dem Gruppen-Template kopiert wird. */
    public String globalTemplate() {
        return globalTemplate;
    }

    /** Ob der Port als Startargument uebergeben wird oder in einer Konfiguration steht. */
    public boolean portAsArgument() {
        return portAsArgument;
    }
}
