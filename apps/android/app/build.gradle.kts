plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// URL is a public endpoint setting, NOT a credential; optional for offline UI smoke.
// Set -PmalakPrivateBaseUrl=https://<your-approved-Malak-gateway> for a connected pilot.
val malakPrivateBaseUrl = providers.gradleProperty("malakPrivateBaseUrl")
    .orElse(providers.environmentVariable("MALAK_PRIVATE_SERVICE_URL"))
    .orElse("")
    .get()
require(malakPrivateBaseUrl.isEmpty() ||
    malakPrivateBaseUrl.matches(Regex("^https://[A-Za-z0-9.-]+(?::[0-9]{2,5})?$"))) {
    "Only an approved HTTPS host:port can configure the Malak private pilot gateway"
}

android {
    namespace = "tn.malak.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tn.malak.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.3-huawei-ocr-qa"
        buildConfigField("String", "MALAK_PRIVATE_SERVICE_URL", "\"$malakPrivateBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // OCR and perspective rectification execute inside the authorized
    // isolated Huawei service. No Tesseract, OpenCV, ONNX or model files in APK.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    testImplementation("junit:junit:4.13.2")
}

