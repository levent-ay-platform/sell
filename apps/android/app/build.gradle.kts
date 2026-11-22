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

val malakV2Discovery = providers.gradleProperty("malakV2Discovery")
    .map { it.toBooleanStrict() }.orElse(false).get()
val malakDirectPublishing = providers.gradleProperty("malakDirectPublishing")
    .map { it.toBooleanStrict() }.orElse(false).get()
val malakDiscoveryBaseUrl = providers.gradleProperty("malakDiscoveryBaseUrl").orElse("").get()
require(malakDiscoveryBaseUrl.isEmpty() ||
    malakDiscoveryBaseUrl.matches(Regex("^https://[A-Za-z0-9.-]+(?::[0-9]{2,5})?$"))) {
    "malakDiscoveryBaseUrl must be a trusted HTTPS origin"
}
val malakMapStyleUrl = providers.gradleProperty("malakMapStyleUrl").orElse("").get()
require(malakMapStyleUrl.isEmpty() ||
    malakMapStyleUrl.matches(Regex("^https://[A-Za-z0-9.-]+(?::[0-9]{2,5})?/[A-Za-z0-9/_\\.-]+$"))) {
    "malakMapStyleUrl must be a public HTTPS style URL"
}

android {
    namespace = "tn.malak.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tn.malak.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 11
        versionName = "0.5.1-date-readout-pilot"
        buildConfigField("String", "MALAK_PRIVATE_SERVICE_URL", "\"$malakPrivateBaseUrl\"")
        buildConfigField("boolean", "MALAK_V2_DISCOVERY", malakV2Discovery.toString())
        buildConfigField("boolean", "MALAK_DIRECT_PUBLISHING", malakDirectPublishing.toString())
        buildConfigField("String", "MALAK_DISCOVERY_BASE_URL", "\"$malakDiscoveryBaseUrl\"")
        buildConfigField("String", "MALAK_MAP_STYLE_URL", "\"$malakMapStyleUrl\"")
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
    // Compress MapLibre's four native ABIs to keep the universal QA APK under the existing size budget.
    packaging { jniLibs.useLegacyPackaging = true }
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
    implementation("org.maplibre.gl:android-sdk-opengl:2026.11.2-globe")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // OCR and perspective rectification execute inside the authorized
    // Mac service. No Tesseract, OpenCV, ONNX or model files in APK.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

