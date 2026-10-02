package de.kevloe.vibecloud.api.identity;

/** Ein angemeldeter Wrapper. */
public record NodeIdentity(String node) implements CallIdentity {

    @Override
    public String describe() {
        return "NODE:" + node;
    }
}
