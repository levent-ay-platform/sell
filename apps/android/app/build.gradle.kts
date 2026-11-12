import java.security.MessageDigest
import java.net.URL

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
        versionCode = 4
        versionName = "0.3.1-ocr-tunisia-qa"
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
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // Offline Arabic CIN text recognition and on-device card geometry.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.opencv:opencv:4.12.0")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    testImplementation("junit:junit:4.13.2")
}


// Only PUBLIC pinned Arabic OCR models are downloaded at *build* time.
// No private identity samples enter Gradle, CI, APK assets or network.
val publicV5Assets = layout.buildDirectory.dir("generated/public-v5-ocr-assets")
android.sourceSets.getByName("main").assets.srcDir(publicV5Assets)

val preparePublicArabicV5 by tasks.registering {
    description = "Fetch SHA256-pinned public Arabic PP-OCRv5 recognition model"
    outputs.dir(publicV5Assets)
    doLast {
        val dir=publicV5Assets.get().asFile.resolve("arabic-v5")
        dir.mkdirs()
        val published=mapOf(
            "inference.onnx" to "799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110",
            "inference.yml" to "21368419e6c016c31db55d316d59e11c128e1913e6e6fe10287084710043d3a6"
        )
        for((name,expectedSha) in published){
            val file=dir.resolve(name)
            fun checksum():String {
                val md=MessageDigest.getInstance("SHA-256")
                file.inputStream().use{input->
                    val buf=ByteArray(16384)
                    while(true){
                        val n=input.read(buf)
                        if(n<0)break
                        md.update(buf,0,n)
                    }
                }
                return md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            }
            if(file.isFile&&checksum().equals(expectedSha,true))continue
            val partial=dir.resolve(name+".partial")
            try {
                val url=URL(
                    "https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx/resolve/main/$name"
                )
                val connection=url.openConnection()
                connection.connectTimeout=15000
                connection.readTimeout=45000
                connection.getInputStream().use { input->
                    partial.outputStream().use{out->input.copyTo(out)}
                }
                if(!partial.renameTo(file) || !checksum().equals(expectedSha,true)){
                    file.delete()
                    throw GradleException("PUBLIC_OCR_MODEL_HASH_MISMATCH: $name")
                }
            }finally{partial.delete()}
        }
    }
}
// Keep offline inference self-contained: all production APKs must bundle
// the exact verified public model; no network is needed on the user's phone.
tasks.matching{
    it.name=="preBuild"||it.name.startsWith("merge")&&it.name.endsWith("Assets")
}.configureEach{dependsOn(preparePublicArabicV5)}
