plugins {
    id("vibecloud.java-conventions")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common:vibecloud-api"))
    implementation(project(":modules:module-api"))

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
