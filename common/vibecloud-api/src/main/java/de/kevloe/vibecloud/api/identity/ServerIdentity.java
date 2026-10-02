package de.kevloe.vibecloud.api.identity;

/** Ein angemeldetes Gameserver- oder Proxy-Plugin. Folgt ab M3. */
public record ServerIdentity(String serverName, String node) implements CallIdentity {

    @Override
    public String describe() {
        return "SERVER:" + serverName;
    }
}
