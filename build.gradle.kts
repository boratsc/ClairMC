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
    description = "Builds the Paper plugin plus the Forge and Fabric server mods."
    dependsOn(":paper-plugin:build", ":forge-server-mod:build", ":fabric-server-mod:build")
}
