plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.theemrock.smooth.bridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    buildFeatures {
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
