plugins {
    `java-library`
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.register<JavaExec>("probeSig") {
    group = "verification"
    description = "Print canonical JSON + signature for a sample response."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.clair.mcbridge.tools.SignatureProbe")

    if (project.hasProperty("probeSecret")) {
        args(project.property("probeSecret").toString())
    }
}
