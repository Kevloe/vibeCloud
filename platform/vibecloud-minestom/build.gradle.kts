plugins { id("vibecloud.java-conventions") }

dependencies {
    // api(), nicht implementation(): Wer diese Bibliothek einbindet, braucht die
    // Cloud-Typen (Component, MessageBundle) im eigenen Compile-Klassenpfad.
    api(project(":common:vibecloud-api"))
    // Minestom ist eine Bibliothek mit eigenem main() - kein Plugin, kein plugin.yml.
    compileOnly(libs.minestom)
}
