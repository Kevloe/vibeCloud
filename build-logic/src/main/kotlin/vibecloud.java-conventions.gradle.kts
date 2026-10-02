// Gilt fuer jedes Java-Modul der Cloud.
plugins {
    `java-library`
}

group = "de.kevloe.vibecloud"
version = "0.1.0-SNAPSHOT"

// Die Version kommt aus gradle.properties, damit ein Wechsel an genau einer Stelle passiert.
val javaVersion: String = providers.gradleProperty("vibecloud.javaVersion").getOrElse("25")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -parameters: Parameternamen bleiben im Bytecode -> bessere Fehlermeldungen
    //              und noetig fuer Reflection-basiertes Mapping (Events, JSON).
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// grpc-protobuf zieht proto-google-common-protos mit: 7,6 MB Protobuf-Definitionen fuer
// Google-Cloud-APIs, die hier nichts zu suchen haben. Sie werden nur fuer die "rich error
// details" von gRPC gebraucht, und die nutzt die Cloud nicht - sie meldet Fehler ueber
// Status.withDescription(). Ausschluss statt Shadow-Filter, damit ein kuenftiger Zugriff
// beim Kompilieren auffaellt und nicht erst zur Laufzeit.
configurations.all {
    exclude(group = "com.google.api.grpc", module = "proto-google-common-protos")
}

dependencies {
    // Logging ist in jedem Modul erlaubt - System.out ist es nicht (PLAN.md Abschnitt 2).
    "implementation"("org.slf4j:slf4j-api:2.0.20")

    "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
    "testImplementation"("org.assertj:assertj-core:3.27.6")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
