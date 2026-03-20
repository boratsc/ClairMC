plugins {
    java
    id("net.minecraftforge.gradle") version "[6.0.24,6.2)"
}

val forgeMcVersion = providers.gradleProperty("forgeMcVersion").get()
val forgeVersion = providers.gradleProperty("forgeVersion").get()

repositories {
    maven("https://maven.minecraftforge.net/")
}

dependencies {
    minecraft("net.minecraftforge:forge:$forgeMcVersion-$forgeVersion")
    implementation(project(":common"))
}

minecraft {
    mappings("official", forgeMcVersion)
    copyIdeResources = true

    runs {
        create("server") {
            workingDirectory(project.file("run-server"))
            property("forge.logging.console.level", "debug")
            mods {
                create("clairmcbridge") {
                    source(sourceSets.main.get())
                }
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(
            "version" to project.version,
            "forgeMcVersion" to forgeMcVersion
        )
    }
}

tasks.jar {
    archiveBaseName.set("clair-mc-bridge-forge")
    from(project(":common").sourceSets.main.get().output)
}
