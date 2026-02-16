# Generate missing gradle-wrapper.jar

When Gradle is available locally, run from project root:

```bash
gradle wrapper
```

or in Android Studio terminal (which often has Gradle tooling available):

```bash
./gradlew wrapper
```

This creates:
- `gradle/wrapper/gradle-wrapper.jar`

After that, normal wrapper commands work:

```bash
./gradlew tasks
./gradlew assembleDebug
```
