plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.airdrop.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.owly.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 103
        versionName = "1.0.3-mt6991"
        ndk { abiFilters += "arm64-v8a" }
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    androidResources { noCompress += "zip" }

    lint { checkReleaseBuilds = true; abortOnError = true }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/radioAssets"))
    testOptions { unitTests.isReturnDefaultValues = true }
}

// The installer scripts live once, in src/main/radio; the APK assets are generated from them.
val syncRadioScripts by tasks.registering(Copy::class) {
    from("src/main/radio") { include("install.sh", "remove.sh") }
    into(layout.buildDirectory.dir("generated/radioAssets"))
    rename { "radio-$it" }
}
tasks.named("preBuild") { dependsOn(syncRadioScripts) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real JSON on the JVM; android.jar only has stubs
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.0.7")
}
