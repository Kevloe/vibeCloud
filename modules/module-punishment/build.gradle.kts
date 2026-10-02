plugins {
    id("vibecloud.java-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    // compileOnly: Modul-API und vibecloud-api kommen vom Master (siehe module-example).
    compileOnly(project(":modules:module-api"))
    // Im Test muss sie auf dem Klassenpfad sein: Der Compiler braucht die Typen,
    // die zur Laufzeit der Master mitbringt.
    testImplementation(project(":modules:module-api"))
    // Das Login-Gate wird gegen eine echte PostgreSQL geprueft: Die Migration des
    // Moduls und die Abfragen sind der Teil, der hier schiefgehen kann.
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgresql)
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testRuntimeOnly(libs.logback.classic)
}

/**
 * Der Paper-Teil des Moduls.
 *
 * <p>Ueber eine eigene Konfiguration statt ueber einen direkten Task-Zugriff: Der laufe
 * sonst quer durch die Projektgrenze und vertraegt sich nicht mit dem Konfigurations-Cache.
 */
val paperBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    paperBundle(project(mapOf(
            "path" to ":modules:module-punishment:bundle-paper",
            "configuration" to "shadow")))
}

tasks.shadowJar {
    archiveFileName = "module-punishment.jar"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    // Der Master packt es beim Serverstart nach plugins/ aus - der Pfad steht in
    // module.json unter "bundles" (PLAN.md Abschnitt 10).
    from(paperBundle) { into("bundles") }

    dependencies {
        exclude(dependency("org.slf4j:.*"))
    }
}

tasks.build { dependsOn(tasks.shadowJar) }
