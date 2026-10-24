plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "it.mototrack"
    compileSdk = 35
    defaultConfig {
        applicationId = "it.mototrack"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        val mapsKey = providers.environmentVariable("MAPS_API_KEY").orElse("").get()
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
        buildConfigField("boolean", "GOOGLE_MAPS_ENABLED", mapsKey.isNotBlank().toString())
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.maps.android:maps-compose:6.4.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
}
