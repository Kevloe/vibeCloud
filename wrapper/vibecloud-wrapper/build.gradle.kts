plugins {
    id("vibecloud.java-conventions")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common:vibecloud-api"))
    implementation(libs.snakeyaml)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.grpc.inprocess)
}

application {
    mainClass = "de.kevloe.vibecloud.wrapper.Main"
    // Netty laedt native Bibliotheken; ohne dieses Flag warnt Java 25 bei jedem Start.
    // Die Warnung im Log wuerde sonst dauerhaft nach einem Problem aussehen.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.shadowJar {
    archiveFileName = "CloudWrapper.jar"
    // INCLUDE ist Pflicht, damit der ServiceFileTransformer die META-INF/services-Dateien
    // von gRPC (NameResolverProvider, ManagedChannelProvider ...) wirklich zusammenfuehrt.
    // Mit EXCLUDE werden Duplikate vorher verworfen und das Fat-Jar faellt erst zur Laufzeit um.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
