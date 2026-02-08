plugins {
    kotlin("jvm") version "2.3.0"
}

group = "com.github.jomof"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        val duration = result.endTime - result.startTime
        println("  ${desc.className} > ${desc.displayName}: ${result.resultType} (${duration}ms)")
    }))
}