plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.simplecityapps.telemetry.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // OpenTelemetry
    api("io.opentelemetry:opentelemetry-api:1.32.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.32.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.32.0")
    implementation("io.opentelemetry.semconv:opentelemetry-semconv:1.23.1-alpha")

    // AndroidX
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.metrics:metrics-performance:1.0.0-alpha04")
    implementation("androidx.core:core-ktx:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Optional dependencies (compileOnly)
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("androidx.navigation:navigation-compose:2.7.6")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.32.0")
}
