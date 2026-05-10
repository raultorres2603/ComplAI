---
name: build
description: "Build the deployable fat JAR for this Micronaut Java project. Outputs to build/libs/complai-all.jar"
argument-hint: "No arguments required"
compatibility: opencode
---

# Build Command

This project uses Gradle with the Shadow plugin to create a deployable fat JAR.

## Build the Fat JAR

```bash
./gradlew clean shadowJar
```

This will produce:
- **Output**: `build/libs/complai-all.jar`

The fat JAR includes all dependencies and is ready for deployment to AWS Lambda.

## Build Outputs

After running the build, you can find:
- JAR file: `build/libs/complai-all.jar`
- Build artifacts: `build/`

## Tips

- The JAR filename may include a short git SHA in CI/deployment pipelines (e.g., `complai-all-<sha8>.jar`)
- To just compile without creating the JAR, use: `./gradlew compileJava`
- To run the application locally, you can use: `./gradlew run` (if configured)