plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.github.kevin58cmd.synthetic-compat")
}
android {
    namespace = "example.synthetic"
    compileSdk = 35
    defaultConfig {
        applicationId = "example.synthetic"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
