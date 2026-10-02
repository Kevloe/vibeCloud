plugins { id("vibecloud.java-conventions") }

// Der SFTP-Zugang, den Master (Templates) und Wrapper (statische Server) beide anbieten.
//
// Ein eigenes Modul und nicht Teil von vibecloud-api: Dort haengen auch die
// Plattform-Plugins dran, und ein SSH-Server hat in einem Paper-Plugin nichts verloren.
dependencies {
    api(libs.sshd.sftp)

    testRuntimeOnly(libs.logback.classic)
}
