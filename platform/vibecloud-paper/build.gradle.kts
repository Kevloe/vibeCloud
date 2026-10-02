plugins {
    id("vibecloud.java-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common:vibecloud-api"))
    // NUR Paper-API. compileOnly: Der Server bringt sie selbst mit.
    compileOnly(libs.paper.api)
    // Fuer Tests der Reflection-Suche gegen echte Bukkit-Typen.
    testImplementation(libs.paper.api)
}

tasks.shadowJar {
    archiveFileName = "vibecloud-paper.jar"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    // Paper bringt protobuf-java 4.29.0 und guava 33.6.0 selbst mit und gewinnt auf dem
    // Klassenpfad. Generierter Protobuf-Code von 4.36.2 wird davon abgelehnt:
    //   "validateProtobufGencodeVersion ... protobuf-java-4.29.0.jar"
    // Deshalb bekommen beide Bibliotheken einen eigenen Namensraum. Konsequent verlagert
    // funktioniert das, weil der generierte Code nur auf com.google.protobuf verweist.
    relocate("com.google.protobuf", "de.kevloe.vibecloud.libs.protobuf")
    relocate("com.google.common", "de.kevloe.vibecloud.libs.guava")
    // Adventure, SLF4J, Gson und snakeyaml bringt Paper selbst mit - doppelt eingepackt
    // gaebe es Klassenkonflikte zur Laufzeit.
    dependencies {
        exclude(dependency("net.kyori:.*"))
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("com.google.code.gson:.*"))
        exclude(dependency("org.yaml:snakeyaml"))
    }
}

tasks.build { dependsOn(tasks.shadowJar) }
