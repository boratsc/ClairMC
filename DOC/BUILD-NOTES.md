# Build notes

If you see "JAVA_HOME is not set" on Windows, run this in PowerShell before building:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
```

Then run the build:

```powershell
.\gradlew.bat clean build
```

## Build commands

Build (standard):

```powershell
.\gradlew.bat clean build
```

Output JAR (shaded) is in build/libs.

Build only JAR (no clean):

```powershell
.\gradlew.bat build
```

Build only shaded JAR:

```powershell
.\gradlew.bat shadowJar
```

## Version bump

Change version in build.gradle.kts:

```kotlin
version = "0.1.2"
```

This version is injected into plugin.yml via processResources.
