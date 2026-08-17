plugins {
    id("com.android.application")
}

android {
    namespace = "dev.cloudwalk"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.cloudwalk"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
}
