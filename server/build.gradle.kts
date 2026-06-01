plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "org.branneman.health"
version = "1.0.0"
application {
    mainClass = "org.branneman.health.ApplicationKt"
}

dependencies {
    api(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}