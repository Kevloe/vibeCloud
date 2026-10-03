package de.kevloe.vibecloud.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Eine Release-Version von Minecraft, Paper oder Velocity zum Vergleichen.
 *
 * <p>Ein Text-Vergleich ist hier falsch: {@code "1.9" > "1.16"} und {@code "26.1" < "1.21"}
 * - beides genau verkehrt. Verglichen wird deshalb Teil fuer Teil als Zahl, ein fehlender
 * Teil zaehlt als 0 ({@code 1.21} = {@code 1.21.0}). Das alte Schema (1.x) und das neue
 * (26.x) lassen sich damit ohne Sonderfall vergleichen: 26 ist groesser als 1.
 *
 * <p>Nur Releases: {@code 1.21.11-rc3}, {@code 1.13-pre7} und {@code 4.2.1-SNAPSHOT} werden
 * abgelehnt. Eine Vorabversion soll niemand versehentlich als Gruppen-Version waehlen.
 */
public record MinecraftVersion(List<Integer> parts) implements Comparable<MinecraftVersion> {

    public MinecraftVersion {
        parts = List.copyOf(parts);
    }

    /** @return die Version, oder leer wenn es keine Release-Version ist */
    public static Optional<MinecraftVersion> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<Integer> parts = new ArrayList<>();
        for (String part : text.trim().split("\\.", -1)) {
            if (part.isEmpty() || part.length() > 4 || !part.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            parts.add(Integer.parseInt(part));
        }
        return parts.size() > 4 ? Optional.empty() : Optional.of(new MinecraftVersion(parts));
    }

    /** Fuer feste Grenzen im Code - ein Tippfehler dort soll sofort auffallen. */
    public static MinecraftVersion of(String text) {
        return parse(text).orElseThrow(() ->
                new IllegalArgumentException("Keine Release-Version: " + text));
    }

    /** Ob {@code text} eine Release-Version ist und mindestens {@code minimum}. */
    public static boolean isAtLeast(String text, MinecraftVersion minimum) {
        return parse(text).map(version -> version.compareTo(minimum) >= 0).orElse(false);
    }

    @Override
    public int compareTo(MinecraftVersion other) {
        int length = Math.max(parts.size(), other.parts.size());
        for (int i = 0; i < length; i++) {
            int mine = i < parts.size() ? parts.get(i) : 0;
            int theirs = i < other.parts.size() ? other.parts.get(i) : 0;
            if (mine != theirs) {
                return Integer.compare(mine, theirs);
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return String.join(".", parts.stream().map(String::valueOf).toList());
    }
}
