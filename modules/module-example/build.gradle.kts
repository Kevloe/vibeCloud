plugins {
    id("vibecloud.java-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    // compileOnly, NICHT implementation: Die Modul-API und vibecloud-api kommen vom
    // Master. Mitgepackt haette das Modul eigene Kopien von CloudModule und EventBus,
    // und der Master koennte es nicht als CloudModule ansprechen.
    compileOnly(project(":modules:module-api"))
}

tasks.shadowJar {
    archiveFileName = "module-example.jar"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // SLF4J gehoert zu den geteilten Paketen und kommt vom Master. Mitgepackt waere es
    // eine zweite Kopie, die der ModuleClassLoader ohnehin ignoriert - nur Ballast.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
    }
}

tasks.build { dependsOn(tasks.shadowJar) }
