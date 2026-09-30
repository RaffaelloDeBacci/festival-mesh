plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.festivalcommunicator.mesh.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":mesh-core"))

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
