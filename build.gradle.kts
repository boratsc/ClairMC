plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "app.clair"
version = "0.1.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val mcVersion = (findProperty("mcVersion") as String?) ?: "1.21.1"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$mcVersion-R0.1-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.11.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("clair-mc-bridge")
}

tasks.shadowJar {
    archiveBaseName.set("clair-mc-bridge")
    archiveClassifier.set("")

    // Minimalnie: brak relokacji. Jeśli kiedyś trafisz na konflikt bibliotek,
    // możesz dodać relocate("com.google.gson", "app.clair.libs.gson").
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.register<JavaExec>("probeSig") {
    group = "verification"
    description = "Print canonical JSON + signature for a sample response (Java implementation)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.clair.mcbridge.tools.SignatureProbe")

    if (project.hasProperty("probeSecret")) {
        args(project.property("probeSecret").toString())
    }
}
