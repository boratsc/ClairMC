plugins {
    base
}

group = "app.clair"
version = "0.2.1"

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds both the Paper plugin and the Forge server mod."
    dependsOn(":paper-plugin:build", ":forge-server-mod:build")
}
