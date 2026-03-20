plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperMcVersion = providers.gradleProperty("paperMcVersion").get()

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:$paperMcVersion-R0.1-SNAPSHOT")
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
    archiveBaseName.set("clair-mc-bridge-paper")
}

tasks.shadowJar {
    archiveBaseName.set("clair-mc-bridge-paper")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
