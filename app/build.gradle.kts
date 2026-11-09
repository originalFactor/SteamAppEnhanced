import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseProperties = Properties().apply {
    val source = rootProject.file("signing.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
val signingFields = mapOf(
    "storeFile" to "ANDROID_KEYSTORE_PATH",
    "storePassword" to "ANDROID_KEYSTORE_PASSWORD",
    "keyAlias" to "ANDROID_KEY_ALIAS",
    "keyPassword" to "ANDROID_KEY_PASSWORD",
)
val releaseSigning = signingFields.mapValues { (property, environment) ->
    providers.environmentVariable(environment).orNull?.takeIf(String::isNotBlank)
        ?: releaseProperties.getProperty(property)?.takeIf(String::isNotBlank)
}
val missingSigningFields = releaseSigning.filterValues { it == null }.keys.toList()
val releaseKeystore = releaseSigning["storeFile"]?.let(rootProject::file)

android {
    namespace = "cc.ptoe.steam.enhanced"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.ptoe.steam.enhanced"
        minSdk = 24
        targetSdk = 37
        versionCode = providers.gradleProperty("releaseVersionCode").orNull?.let {
            requireNotNull(it.toIntOrNull()?.takeIf { code -> code in 1..2_100_000_000 }) {
                "releaseVersionCode must be an integer between 1 and 2100000000."
            }
        } ?: 1
        versionName = providers.gradleProperty("releaseVersionName").orElse("1.0").get()
    }

    androidResources {
        localeFilters += listOf("zh-rCN", "en-rUS")
    }

    signingConfigs {
        if (missingSigningFields.isEmpty()) create("production") {
            storeFile = releaseKeystore
            storeType = "PKCS12"
            storePassword = releaseSigning["storePassword"]
            keyAlias = releaseSigning["keyAlias"]
            keyPassword = releaseSigning["keyPassword"]
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("production")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(missingSigningFields.isEmpty()) {
            "Release signing is required. Configure signing.properties or the ANDROID_* signing environment variables. Missing: ${missingSigningFields.joinToString()}."
        }
        check(releaseKeystore?.isFile == true) { "The configured release keystore does not exist." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
