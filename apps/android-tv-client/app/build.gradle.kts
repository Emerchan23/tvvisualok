plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.paineltv.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.paineltv.client"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
