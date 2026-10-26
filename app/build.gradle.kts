plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Keep the dependency graph small: this project is never built on a dev machine, so anything that
// only resolves at build time can only fail once it reaches the CI runner.
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
        versionName = "0.4.7" + (project.findProperty("gitSha") as String?)?.let { "-$it" }.orEmpty()
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
    // Ships inside the APK: this runs in the target app's process, where it is the only way to
    // reflect on Location.mMock / Location.readFromParcel without a greylist exception.
    implementation(libs.hiddenapibypass)
    // Provided by the framework at load time; bundling it would collide with the host's copy.
    compileOnly(libs.libxposed.api)
}
