plugins { id("vibecloud.java-conventions") }

dependencies {
    // Was ein Modul sieht, sieht es ueber dieses Modul - daher durchgehend api().
    api(project(":common:vibecloud-protocol"))
    api(project(":common:vibecloud-common"))
    api(libs.adventure.minimessage)
}
