plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Zero runtime dependencies on purpose: the smaller the graph, the fewer things that can
// only fail once they hit the CI runner (this project is never built on a dev machine).
android {
    namespace = "com.mockloc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mockloc"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        // CI passes -PgitSha=<7 chars> so `dumpsys package com.mockloc | grep versionName` on the
        // phone identifies the exact build; without it every artifact says the same version and
        // "is the new code actually installed?" becomes guesswork.
        versionName = "0.3.0" + (project.findProperty("gitSha") as String?)?.let { "-$it" }.orEmpty()
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.osmdroid.android)
    compileOnly(libs.libxposed.api)
}
