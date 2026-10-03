plugins {
    id("vibecloud.java-conventions")
    alias(libs.plugins.protobuf)
}

dependencies {
    // api(), nicht implementation(): Wer das Protokoll nutzt, braucht die
    // generierten Typen auch im eigenen Compile-Klassenpfad.
    api(libs.protobuf.java)
    api(libs.bundles.grpc.runtime)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        all().configureEach { plugins { create("grpc") } }
    }
}

// Java 17 statt der Projekt-Version: Das Legacy-Plugin fuer Paper 1.16 bis 26.1 laeuft
// auf Java 17 und packt dieses Jar mit ein. Der generierte Code ist ohnehin Java 8 - an
// Master und Wrapper aendert das nichts.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}
