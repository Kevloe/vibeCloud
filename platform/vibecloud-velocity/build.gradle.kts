plugins {
    id("vibecloud.java-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    // implementation statt compileOnly: Das Plugin muss gRPC und die API mitbringen,
    // der Proxy hat sie nicht.
    implementation(project(":common:vibecloud-api"))
    // NUR Velocity-API. Kein Bukkit, kein Minestom (PLAN.md Abschnitt 4).
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
}

tasks.shadowJar {
    archiveFileName = "vibecloud-velocity.jar"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    // Paper bringt protobuf-java 4.29.0 und guava 33.6.0 selbst mit und gewinnt auf dem
    // Klassenpfad. Generierter Protobuf-Code von 4.36.2 wird davon abgelehnt:
    //   "validateProtobufGencodeVersion ... protobuf-java-4.29.0.jar"
    // Deshalb bekommen beide Bibliotheken einen eigenen Namensraum. Konsequent verlagert
    // funktioniert das, weil der generierte Code nur auf com.google.protobuf verweist.
    relocate("com.google.protobuf", "de.kevloe.vibecloud.libs.protobuf")
    relocate("com.google.common", "de.kevloe.vibecloud.libs.guava")
    // Adventure und SLF4J bringt Velocity selbst mit - doppelt eingepackt gaebe es
    // Klassenkonflikte zur Laufzeit.
    dependencies {
        exclude(dependency("net.kyori:.*"))
        exclude(dependency("org.slf4j:.*"))
    }
}

tasks.build { dependsOn(tasks.shadowJar) }
