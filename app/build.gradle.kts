import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val apkBuildTimestamp = LocalDateTime.now()
    .format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))

android {
    namespace = "com.theemrock.smooth.sensus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.theemrock.smooth.sensus"
        minSdk = 33
        targetSdk = 35
        versionCode = 6
        versionName = "0.0.6"

        buildConfigField("String", "BUILD_TIMESTAMP", "\"$apkBuildTimestamp\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("smoothRelease") {
            val signingKeys = listOf(
                "SMOOTH_SIGNING_STORE_FILE",
                "SMOOTH_SIGNING_STORE_PASSWORD",
                "SMOOTH_SIGNING_KEY_ALIAS",
                "SMOOTH_SIGNING_KEY_PASSWORD"
            )
            val signingValues = signingKeys.associateWith {
                providers.environmentVariable(it).orNull
            }
            val configured = signingValues.values.any { !it.isNullOrBlank() }

            if (configured) {
                signingKeys.forEach { name ->
                    require(!signingValues[name].isNullOrBlank()) {
                        "Missing signing environment variable: $name"
                    }
                }
                val path = signingValues.getValue("SMOOTH_SIGNING_STORE_FILE")!!
                require(file(path).isFile) {
                    "Release keystore file not found"
                }
                storeFile = file(path)
                storePassword = signingValues.getValue("SMOOTH_SIGNING_STORE_PASSWORD")
                keyAlias = signingValues.getValue("SMOOTH_SIGNING_KEY_ALIAS")
                keyPassword = signingValues.getValue("SMOOTH_SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("smoothRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release", "debug")
            externalNativeBuild {
                cmake {
                    arguments += "-DSMOOTH_OPTIMIZED_ARM=ON"
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("benchmark")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    
    // Room components
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Gson
    implementation(libs.gson)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
