// Toolchain-Resolver: lädt das benötigte JDK bei Bedarf automatisch nach,
// damit niemand manuell ein JDK installieren muss.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Convention-Plugins liegen in einem eigenen Build, nicht in buildSrc:
// So wird bei einer Änderung dort nicht der ganze Haupt-Build neu konfiguriert.
includeBuild("build-logic")

rootProject.name = "vibeCloud"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    }
}

// --- Reihenfolge entspricht der Abhängigkeitsrichtung aus PLAN.md Abschnitt 4 ---
include(
    ":common:vibecloud-protocol",
    ":common:vibecloud-common",
    ":common:vibecloud-api",
    // Nur fuer Master und Wrapper - siehe build.gradle.kts dort.
    ":common:vibecloud-sftp",
    ":master:vibecloud-master",
    ":wrapper:vibecloud-wrapper",
    ":platform:vibecloud-velocity",
    ":platform:vibecloud-paper",
    // Dasselbe fuer Paper 1.16 bis 26.1, gebaut fuer Java 17.
    ":platform:vibecloud-paper-legacy",
    ":platform:vibecloud-minestom",
    ":modules:module-api",
    ":modules:module-punishment",
    // Der Paper-Teil des Punishment-Moduls. Landet als bundles/paper.jar im
    // Modul-JAR und wird von dort in die Gameserver verteilt.
    ":modules:module-punishment:bundle-paper",
    ":modules:module-example",
)
