plugins {
    id("vibecloud.java-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    // Der Paper-Teil des Moduls laeuft im Gameserver, nicht im Master. Er sieht deshalb
    // das Plattform-Plugin, nicht die Modul-API.
    compileOnly(project(":platform:vibecloud-paper"))
    compileOnly(project(":common:vibecloud-api"))
    compileOnly(libs.paper.api)

    // Wegen der Frage-/Antwort-Records aus dem exportierten api-Paket. Alles andere
    // daraus wird unten wieder ausgeschlossen.
    implementation(project(":modules:module-punishment"))
}

tasks.shadowJar {
    archiveFileName = "paper.jar"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    // Vom Modul darf nur das exportierte api-Paket mit: Die Records gehen als JSON ueber
    // die Leitung, also muss ihr Aufbau auf beiden Seiten gleich sein. Der Master-Teil
    // dagegen hat im Gameserver nichts verloren - er wuerde dort nur die Modul-API
    // suchen, die es hier nicht gibt.
    exclude("de/kevloe/vibecloud/punishment/*.class")
    exclude("module.json")
    exclude("db/**")
    exclude("messages/**")

    // Paper bringt SLF4J selbst mit.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
    }
}

tasks.build { dependsOn(tasks.shadowJar) }
