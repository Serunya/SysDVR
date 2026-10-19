plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tailspin.sysdvr.usb"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        // bulkTransfer() is limited to 16 KB per call before API 28, SysDVR packets are up to ~336 KB
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
