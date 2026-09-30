plugins {
    kotlin("multiplatform")
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "FestivalMeshIOS"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":mesh-core"))
        }
    }
}
