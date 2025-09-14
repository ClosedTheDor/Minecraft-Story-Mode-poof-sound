# PoofMod (Fabric 1.20.1) — three sounds, corrected wrapper config

This project contains three poof sounds and is configured to use Fabric Loom 1.9.2
(which requires Gradle 8.11+). The gradle wrapper properties point to Gradle 8.11.

**Important:** The included `gradle/wrapper/gradle-wrapper.jar` is a placeholder (empty file).
To make `./gradlew` runnable, either:
  - On a machine with `gradle` installed run: `gradle wrapper --gradle-version 8.11`
    This will generate a proper `gradle-wrapper.jar` and scripts.
  - Or copy a real `gradle-wrapper.jar` produced for Gradle 8.11 into `gradle/wrapper/`.

After that run `./gradlew build` to build the mod JAR.
