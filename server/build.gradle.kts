plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "org.branneman.health"
version = "1.0.0"
application {
    mainClass = "org.branneman.health.ApplicationKt"
}

sourceSets {
    create("apiTest") {
        kotlin.srcDir("src/apiTest/kotlin")
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += output + compileClasspath
    }
}

val apiTestImplementation by configurations.getting

dependencies {
    api(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.jbcrypt)
    implementation(libs.ktor.serverForwardedHeader)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    apiTestImplementation(libs.ktor.clientCore)
    apiTestImplementation(libs.ktor.clientCio)
    apiTestImplementation(libs.ktor.clientContentNegotiation)
    apiTestImplementation(libs.ktor.clientSerializationJson)
    apiTestImplementation(libs.kotlin.testJunit)
    apiTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.register<Test>("apiTest") {
    description = "Run API tests against the live server"
    group = "verification"
    testClassesDirs = sourceSets["apiTest"].output.classesDirs
    classpath = sourceSets["apiTest"].runtimeClasspath
}

tasks.test {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, raw) = line.split("=", limit = 2)
                val value = raw.trim().removeSurrounding("\"").removeSurrounding("'")
                environment(key.trim(), value)
            }
    }
}