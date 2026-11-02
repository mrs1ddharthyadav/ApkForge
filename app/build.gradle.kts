plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sangam.apkforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sangam.apkforge"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
    }

    // One fixed key for every build, so each new version installs as an update
    // instead of failing with "App not installed" (signature mismatch).
    // Fine for a personal project; use a private key if you ever publish on a store.
    signingConfigs {
        create("forge") {
            storeFile = file("forge-build.p12")
            storePassword = "forgebuild"
            keyAlias = "forgebuild"
            keyPassword = "forgebuild"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("forge")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("forge")
        }
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
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Google's official APK signing and verification library
    implementation(libs.apksig)

    // ARSCLib by REAndroid (Apache 2.0): reads and writes Android binary XML and resources.
    // Same build that APKEditor ships, kept in app/libs so its API never changes under us.
    implementation(files("libs/ARSCLib.jar"))
}
