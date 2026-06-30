import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.uiToolingPreview)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientAndroid)
    implementation(libs.ktor.clientContentNegotiation)
    implementation(libs.ktor.clientSerializationJson)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    debugImplementation(libs.compose.uiTooling)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.workmanager)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.uiTestJunit4)
    debugImplementation(libs.compose.uiTestManifest)
    androidTestImplementation(libs.compose.uiTestJunit4)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.browser)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.reorderable)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "org.branneman.health"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "org.branneman.health"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        val gitCount = runCatching {
            providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
                .standardOutput.asText.get().trim().toInt()
        }.getOrDefault(1)
        val gitHash = runCatching {
            providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText.get().trim()
        }.getOrDefault("unknown")
        versionCode = gitCount
        versionName = "$gitCount-$gitHash"
        buildConfigField(
            "String", "SERVER_BASE_URL",
            "\"${System.getenv("SERVER_BASE_URL") ?: localProps.getProperty("server.baseUrl") ?: error("SERVER_BASE_URL env var or server.baseUrl in local.properties must be set")}\""
        )
        val e2ePassword = (project.findProperty("e2ePassword") as String?)
            ?: System.getenv("E2E_PASSWORD")
            ?: ""
        buildConfigField("String", "E2E_PASSWORD", "\"$e2ePassword\"")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}