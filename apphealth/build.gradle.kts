import java.util.Base64

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

group = "com.simplecityapps"
version = "0.2.0"

android {
    namespace = "com.simplecityapps.apphealth.android"
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

    kotlin {
        jvmToolchain(17)
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
    implementation("androidx.core:core-ktx:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.21")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.32.0")
}

// GPG signing key - accepts either ASCII-armored or base64-encoded format
val signingKeyRaw: String? = System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey")
    ?: providers.gradleProperty("signingInMemoryKey").orNull

fun decodeSigningKey(raw: String): String? {
    if (raw.startsWith("-----BEGIN PGP")) return raw
    return try {
        val decoded = String(Base64.getDecoder().decode(raw))
        if (decoded.startsWith("-----BEGIN PGP")) decoded else null
    } catch (_: Exception) { null }
}

val signingKey: String? = signingKeyRaw?.let { decodeSigningKey(it) }

// Set decoded key for the maven-publish plugin to use
if (signingKey != null) {
    ext.set("signingInMemoryKey", signingKey)
}

mavenPublishing {
    coordinates("com.simplecityapps", "app-health-android", version.toString())
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    if (signingKey != null) {
        signAllPublications()
    }

    pom {
        name.set("App Health Android")
        description.set("Android SDK for crash handling, performance metrics, and telemetry built on OpenTelemetry")
        url.set("https://github.com/timusus/app-health-android")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("timusus")
                name.set("Tim Malseed")
                email.set("t.malseed@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/timusus/app-health-android")
            connection.set("scm:git:git://github.com/timusus/app-health-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/timusus/app-health-android.git")
        }
    }
}
