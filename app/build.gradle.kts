plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pdfarchitect.starter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pdfarchitect.starter"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui:1.9.2")
    implementation("androidx.compose.foundation:foundation:1.9.2")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.2")
}

// PDFBox-Android keeps the source PDF's selectable text and vector geometry
// when appending ink, text stamps and images to a copy of the original.
dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
}
