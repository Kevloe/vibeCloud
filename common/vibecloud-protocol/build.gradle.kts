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
