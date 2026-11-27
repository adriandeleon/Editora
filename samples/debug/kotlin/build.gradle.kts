// Sample Gradle build in the Kotlin DSL, for Editora's Kotlin debugger and its Gradle support. The `application`
// plugin gives the `run` task that Debug via Build Tool starts under a suspended JVM; `gradle run` runs the shelf,
// `gradle run --args=crash` ends on an uncaught exception, `gradle test` runs ShelfTest. Gradle fetches the Kotlin
// plugin and JUnit on first use. The custom `hello` task shows under "Load all tasks…".
plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "shelf.MainKt" // the top-level main in Main.kt
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("hello") {
    doLast {
        println("Hello from the Kotlin DSL sample build.")
    }
}
