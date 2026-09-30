plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("com.android.library") version "8.7.2" apply false
}

subprojects {
    group = "com.festivalcommunicator"
    version = "0.1.0-SNAPSHOT"
}
