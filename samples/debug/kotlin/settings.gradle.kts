// The shelf debugging demo, as a standalone Gradle build written in the Kotlin DSL (the repo's own build never
// picks it up). This file makes the folder the build's root, which is where the Kotlin language server imports
// the build from (build.gradle.kts is what shows the Gradle toolbar button).
rootProject.name = "shelf-kotlin"
