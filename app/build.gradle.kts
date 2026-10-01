plugins {
    id("com.android.application")
}

android {
    namespace = "ernest.ascrcpy.adb.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "ernest.ascrcpy.adb.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":adb"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
