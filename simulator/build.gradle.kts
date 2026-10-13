// simulator: JVM CLI that drives core's glucose model and streams NDJSON over TCP. See ADR-0011.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
}

application {
    mainClass.set("com.fakeglucosense.simulator.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
