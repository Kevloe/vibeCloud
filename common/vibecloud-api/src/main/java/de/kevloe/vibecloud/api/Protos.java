package de.kevloe.vibecloud.api;

import de.kevloe.vibecloud.protocol.Timestamp;
import de.kevloe.vibecloud.protocol.Uuid;

import java.time.Instant;

/** Umwandlung zwischen Protobuf-Typen und den Java-Typen, mit denen der Code arbeitet. */
public final class Protos {

    public static Uuid toProto(java.util.UUID uuid) {
        return Uuid.newBuilder()
                .setMostSignificantBits(uuid.getMostSignificantBits())
                .setLeastSignificantBits(uuid.getLeastSignificantBits())
                .build();
    }

    public static java.util.UUID fromProto(Uuid uuid) {
        return new java.util.UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    /** Immer UTC - Umrechnung passiert erst bei der Anzeige (PLAN.md Abschnitt 8). */
    public static Timestamp toProto(Instant instant) {
        return Timestamp.newBuilder().setEpochMillis(instant.toEpochMilli()).build();
    }

    public static Instant fromProto(Timestamp timestamp) {
        return Instant.ofEpochMilli(timestamp.getEpochMillis());
    }

    public static Timestamp now() {
        return toProto(Instant.now());
    }

    private Protos() {
    }
}
