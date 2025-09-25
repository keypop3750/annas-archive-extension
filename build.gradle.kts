plugins {
    id("com.android.application")
    kotlin("android")
    id("kotlinx-serialization")
}

android {
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.all.annasarchive"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")
    compileOnly("androidx.core:core-ktx:1.12.0")
    
    // Tachiyomi source API
    compileOnly("eu.kanade.tachiyomi:source-api:1.5")
    
    // HTTP client
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    
    // HTML parsing
    compileOnly("org.jsoup:jsoup:1.17.1")
    
    // Serialization
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // RxJava
    compileOnly("io.reactivex:rxjava:1.3.8")
}

tasks.register<Jar>("jar") {
    dependsOn("assembleRelease")
    from(zipTree("build/outputs/apk/release/app-release-unsigned.apk"))
    archiveFileName.set("anna-archive-v${android.defaultConfig.versionName}.jar")
    destinationDirectory.set(file("build/"))
}