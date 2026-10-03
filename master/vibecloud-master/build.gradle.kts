plugins {
    id("vibecloud.java-conventions")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common:vibecloud-api"))
    implementation(project(":modules:module-api"))
    implementation(project(":common:vibecloud-sftp"))

    implementation(libs.grpc.services)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.lettuce)
    implementation(libs.caffeine)
    implementation(libs.javalin)
    implementation(libs.jline)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)
    // Nicht runtimeOnly: TerminalAppender ist eine Logback-Appender-Implementierung.
    // Die einzige Stelle, die Logback direkt kennt - sonst nur SLF4J.
    implementation(libs.logback.classic)

    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testRuntimeOnly(libs.logback.classic)
}

application {
    mainClass = "de.kevloe.vibecloud.master.Main"
    // Netty laedt native Bibliotheken; ohne dieses Flag warnt Java 25 bei jedem Start.
    // Die Warnung im Log wuerde sonst dauerhaft nach einem Problem aussehen.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.shadowJar {
    // Fester Name, damit Start-Skripte nicht an der Version haengen.
    archiveFileName = "CloudMaster.jar"
    // INCLUDE ist Pflicht, damit der ServiceFileTransformer die META-INF/services-Dateien
    // von gRPC (NameResolverProvider, ManagedChannelProvider ...) wirklich zusammenfuehrt.
    // Mit EXCLUDE werden Duplikate vorher verworfen und das Fat-Jar faellt erst zur Laufzeit um.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }

// --- Was die Master-Jar mitbringt, damit ein Root nur sie braucht ---
//
// Die drei Plattform-Plugins: Der Master packt sie beim Start nach templates/global/
// aus (BundledFiles). Module kommen bewusst NICHT mit - die legt der Betreiber selbst
// nach modules/.
fun pluginJar(path: String): Configuration = configurations.create(
    "bundled" + path.substringAfterLast(':').split('-').joinToString("") {
        it.replaceFirstChar(Char::uppercase)
    }
) {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}.also { dependencies.add(it.name, dependencies.project(mapOf("path" to path,
        "configuration" to "pluginJarElements"))) }

val paperPlugin = pluginJar(":platform:vibecloud-paper")
val legacyPlugin = pluginJar(":platform:vibecloud-paper-legacy")
val velocityPlugin = pluginJar(":platform:vibecloud-velocity")

// Das Dashboard baut npm, nicht Gradle (PLAN.md Abschnitt 4). Ist es gebaut, kommt es mit;
// sonst laeuft der Master nur mit der Schnittstelle. 'releaseJars' im Root-Build besteht
// darauf, dass es da ist.
val bundledResources = tasks.register<Sync>("bundledResources") {
    into(layout.buildDirectory.dir("generated/bundled"))
    // Die Pfade unter bundled/ sind die Zielpfade im Arbeitsverzeichnis des Masters -
    // BundledFiles.TARGETS muss dieselben kennen.
    from(paperPlugin) { into("bundled/templates/global/server/plugins") }
    from(legacyPlugin) { into("bundled/templates/global/server-legacy/plugins") }
    from(velocityPlugin) { into("bundled/templates/global/proxy/plugins") }
    from(rootProject.layout.projectDirectory.dir("dashboard/dist")) { into("dashboard") }
}

// Nur in die Jar, nicht auf den Test-Klassenpfad: Die Tests sollen nicht an einem Build
// der Plugins und des Dashboards haengen.
tasks.shadowJar { from(bundledResources) }
