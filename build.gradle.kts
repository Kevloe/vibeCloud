// Der Root-Build enthaelt absichtlich keine Logik fuer Subprojekte.
// Gemeinsame Einstellungen stehen in build-logic/ als Convention-Plugins,
// damit jedes Modul sichtbar erklaert, was fuer es gilt.
tasks.register("versions") {
    group = "help"
    description = "Zeigt die wichtigsten Versionen dieses Builds."
    val java = providers.gradleProperty("vibecloud.javaVersion")
    val gradleVersion = gradle.gradleVersion
    doLast {
        println("Gradle : $gradleVersion")
        println("Java   : ${java.get()}")
    }
}

/**
 * Kopiert die gebauten Jars in die Testumgebung.
 *
 * Ziel steht in gradle.properties unter "vibecloud.testEnv" (relativ zum Projekt).
 * Nach jeder Aenderung zusammen mit dem Build ausfuehren:
 *
 *   ./gradlew build updateTestEnv
 *
 * Bewusst ein eigener Task und kein Copy: Unter Windows haelt eine laufende JVM ihr Jar
 * gesperrt. Dann soll dastehen, welche Datei nicht ersetzt wurde, statt dass der Build
 * mit einer Zugriffsverletzung abbricht.
 *
 * In doLast stecken nur serialisierbare Werte - Provider und File. Ein Zugriff auf
 * project oder rootDir von dort aus waere eine Script-Referenz, und die kann der
 * Konfigurations-Cache nicht speichern.
 */
tasks.register("updateTestEnv") {
    group = "vibecloud"
    description = "Kopiert Master, Wrapper, Module und Plugins in die Testumgebung."

    // Lokal, nicht auf Script-Ebene: Eine Variable des Build-Scripts wuerde von doLast aus
    // das Script selbst mitnehmen, und genau das kann der Konfigurations-Cache nicht.
    val projectRoot: java.io.File = rootDir
    val testEnvironment = providers.gradleProperty("vibecloud.testEnv")

    // Modul -> Zielpfad in der Testumgebung.
    val jars = listOf(
        ":master:vibecloud-master" to "master/CloudMaster.jar",
        ":wrapper:vibecloud-wrapper" to "wrapper/CloudWrapper.jar",
        ":modules:module-punishment" to "master/modules/module-punishment.jar",
        ":platform:vibecloud-paper"
            to "master/templates/global/server/plugins/vibecloud-paper.jar",
        // Paper unter 26.2 bekommt statt des obigen dieses (TemplateStore).
        ":platform:vibecloud-paper-legacy"
            to "master/templates/global/server-legacy/plugins/vibecloud-paper-legacy.jar",
        ":platform:vibecloud-velocity"
            to "master/templates/global/proxy/plugins/vibecloud-velocity.jar",
    )

    // Der ganze Build, nicht nur die Jars: So wird nie eine Datei kopiert, die an einem
    // fehlgeschlagenen Test vorbeigekommen ist. "./gradlew updateTestEnv" allein genuegt
    // damit nach einer Aenderung.
    // Nur die echten Module: ":common" und ":master" sind blosse Ordner in
    // settings.gradle.kts, haben kein Build-Script und damit auch keinen build-Task.
    subprojects.filter { it.buildFile.exists() }
        .forEach { dependsOn(it.path + ":build") }

    val copies = jars.map { (path, relative) ->
        project(path).layout.buildDirectory
            .file("libs/" + relative.substringAfterLast('/')) to relative
    }
    val target = testEnvironment.map { projectRoot.resolve(it) }

    doLast {
        val directory = target.orNull
        if (directory == null || !directory.isDirectory) {
            println("Keine Testumgebung unter " + (directory ?: "<nicht gesetzt>")
                + " - nichts zu tun.")
            return@doLast
        }

        val locked = mutableListOf<String>()
        var copied = 0

        for ((source, relative) in copies) {
            val destination = directory.resolve(relative)
            destination.parentFile.mkdirs()
            try {
                source.get().asFile.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            } catch (exception: java.io.IOException) {
                locked += relative
            }
        }

        // Das gebaute Dashboard mit uebertragen, falls es eines gibt. Gebaut wird es mit
        // npm (PLAN.md Abschnitt 4: kein Gradle) - hier wird nur kopiert.
        java.io.File(projectRoot, "dashboard/dist").let { dist ->
            if (dist.isDirectory) {
                val target = directory.resolve("master/dashboard")
                target.deleteRecursively()
                dist.copyRecursively(target, overwrite = true)
                println("Dashboard uebertragen -> $target")
            }
        }

        println("Testumgebung aktualisiert: $copied von ${copies.size} Dateien -> $directory")
        if (locked.isNotEmpty()) {
            println("NICHT ersetzt, weil in Benutzung: " + locked.joinToString(", "))
            println("Master, Wrapper oder Gameserver laufen noch - beenden "
                + "und erneut ausfuehren.")
        }
    }
}
