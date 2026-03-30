plugins {
    java
    id("net.neoforged.moddev") version "2.0.141"
}

val neoforgeMcVersion = providers.gradleProperty("neoforgeMcVersion").get()
val neoforgeVersion = providers.gradleProperty("neoforgeVersion").get()

version = rootProject.version
group = rootProject.group

repositories {
    mavenCentral()
}

base {
    archivesName.set("clair-mc-bridge-neoforge")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

neoForge {
    version = neoforgeVersion

    runs {
        register("server") {
            server()
            programArguments.add("--nogui")
        }
    }

    mods {
        register("clairmcbridge") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(project(":common"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta")) {
        expand(
            "version" to project.version,
            "neoforgeMcVersion" to neoforgeMcVersion,
            "neoforgeVersion" to neoforgeVersion
        )
    }
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
}
