// Das Cloud-Plugin fuer Paper 1.16 bis 26.1.
//
// vibecloud-paper ist fuer Java 25 und api-version 26.2 gebaut - schon 26.1 lehnt es ab.
// Dieses Plugin laeuft auf Java 17 gegen die Bukkit-API von 1.16.5.
//
// Keine zweite Verbindung und keine zweite Rechte-Auswertung: Die Dateien, die beides
// enthalten, werden aus vibecloud-api, vibecloud-common und vibecloud-paper hierher
// kopiert und mit --release 17 noch einmal kompiliert. Benutzt dort jemand eine API ab
// Java 18, faellt genau dieser Build um - und nicht erst ein 1.16-Server im Betrieb.
//
// Zwei Teile: src/bootstrap (Java 8) ist die Plugin-Klasse, die Bukkit laedt; src/main
// (Java 17) ist der Rest und liegt als eingebettetes core.jar im Plugin. Warum, steht in
// VibeCloudPaperLegacy: Paper 1.16 kann Java-17-Klassen nicht umschreiben und schreibt
// sonst fuer jede einen ERROR ins Log.
plugins {
    id("vibecloud.java-conventions")
    alias(libs.plugins.shadow)
}

val sharedSources = tasks.register<Sync>("sharedSources") {
    into(layout.buildDirectory.dir("generated/sources/shared"))
    from(rootProject.file("common/vibecloud-api/src/main/java")) {
        include("de/kevloe/vibecloud/api/Protos.java")
        include("de/kevloe/vibecloud/api/plugin/**")
        include("de/kevloe/vibecloud/api/permission/**")
    }
    from(rootProject.file("common/vibecloud-common/src/main/java")) {
        include("de/kevloe/vibecloud/common/VibeCloud.java")
        include("de/kevloe/vibecloud/common/message/**")
        include("de/kevloe/vibecloud/common/tls/**")
    }
    // Die Rechte-Bridge und die Team-Sortierung sind reine Bukkit-API und auf 1.16
    // dieselben wie auf 26.2.
    from(rootProject.file("platform/vibecloud-paper/src/main/java")) {
        include("de/kevloe/vibecloud/paper/CloudPermissible.java")
        include("de/kevloe/vibecloud/paper/PermissibleInjector.java")
        include("de/kevloe/vibecloud/paper/RankTeams.java")
    }
}

sourceSets.main {
    java.srcDir(sharedSources)
}

val bootstrap: SourceSet = sourceSets.create("bootstrap")

tasks.withType<JavaCompile>().configureEach {
    // Der Lader muss fuer das ASM von Paper 1.16 lesbar sein. Alles andere laeuft im
    // eigenen Klassenlader und braucht die Records der gemeinsamen Dateien.
    if (name == bootstrap.compileJavaTaskName) {
        options.release = 8
        // "Quellwert 8 ist veraltet" - bewusst, siehe oben.
        options.compilerArgs.add("-Xlint:-options")
    } else {
        options.release = 17
    }
}

dependencies {
    // Das Protokoll ist schon fuer Java 17 gebaut (siehe dessen build.gradle.kts).
    implementation(project(":common:vibecloud-protocol"))
    implementation(libs.gson)
    implementation(libs.snakeyaml)
    // Die gemeinsamen Klassen loggen ueber SLF4J. Paper 1.16 hat keinen Anbieter dafuer -
    // ohne den liefen Verbindungsfehler ins Leere. slf4j-jdk14 schreibt nach
    // java.util.logging und damit in die Serverkonsole.
    implementation(libs.slf4j.jdk14)
    implementation(libs.adventureLegacy.minimessage)
    implementation(libs.adventureLegacy.serializerLegacy)
    implementation(libs.adventureLegacy.serializerPlain)

    // NUR Paper-/Bukkit-API von 1.16.5 - der Server bringt sie mit.
    compileOnly(libs.paper.api.legacy)
    "bootstrapCompileOnly"(libs.paper.api.legacy)
    // CloudCore liegt im Lader und kommt zur Laufzeit von dort - nicht mit einpacken.
    compileOnly(bootstrap.output)

    testImplementation(libs.paper.api.legacy)
    testImplementation(bootstrap.output)
}

tasks.shadowJar {
    // Der Kern - landet als META-INF/vibecloud/core.jar im Plugin.
    archiveFileName = "vibecloud-paper-legacy-core.jar"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    // Alles, was ein Server dieser Versionen selbst in einer anderen Version haben kann,
    // bekommt einen eigenen Namensraum: Paper 1.16 bringt gson 2.8, snakeyaml 1.x, guava 21
    // und ab 1.16.5 ein altes Adventure mit. Wer gewinnt, entscheidet der Klassenlader -
    // also darf es keine Ueberschneidung geben.
    val libs = "de.kevloe.vibecloud.libs"
    relocate("com.google.protobuf", "$libs.protobuf")
    relocate("com.google.common", "$libs.guava")
    relocate("com.google.gson", "$libs.gson")
    relocate("org.yaml.snakeyaml", "$libs.snakeyaml")
    relocate("net.kyori", "$libs.kyori")
    relocate("org.slf4j", "$libs.slf4j")
}

// Das ausgelieferte Plugin: der Lader, plugin.yml und config.yml, dazu der Kern als Datei.
// Unter META-INF sieht Bukkit ihn nicht als Klassen und schreibt ihn deshalb nicht um.
val pluginJar = tasks.register<Jar>("pluginJar") {
    archiveFileName = "vibecloud-paper-legacy.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    from(bootstrap.output)
    from(tasks.shadowJar) {
        into("META-INF/vibecloud")
        rename { "core.jar" }
    }
}

tasks.build { dependsOn(pluginJar) }

// Die fertige Plugin-Jar fuer die Master-Jar: Der Master packt sie beim Start nach
// templates/global/.../plugins/ aus, damit ein Root nur CloudMaster.jar braucht.
val pluginJarElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts { add(pluginJarElements.name, pluginJar) }
