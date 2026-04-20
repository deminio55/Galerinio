plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.galerinio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.galerinio"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        dataBinding = false
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module"
            )
            pickFirsts += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
    lint {
        abortOnError = false
        disable += "MissingClass"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    
    // Glide for image loading and caching
    implementation(libs.glide)
    ksp(libs.glide.compiler)
    
    // Navigation
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    
    // Fragment & SwipeRefresh
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    
    // ViewPager2 for image viewing
    implementation(libs.androidx.viewpager2)

    // Embedded video player (AndroidX Media3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    
    // DataStore for preferences
    implementation(libs.androidx.datastore.preferences)

    // Biometric auth for app lock
    implementation(libs.androidx.biometric)

    // Splash screen API
    implementation(libs.androidx.splashscreen)

    // WorkManager for background tasks
    implementation(libs.androidx.work.runtime)
    
    // Coil (alternative to Glide)
    implementation(libs.coil)

    // Google Maps
    implementation(libs.play.services.maps)
    implementation(libs.android.maps.utils)

    // Cloud storage providers
    implementation(libs.sardine.android)      // WebDAV
    implementation(libs.smbj)                  // SMB
    implementation(libs.sshj)                  // SFTP
    implementation(libs.bouncycastle.bcprov)    // Crypto provider for SFTP
    implementation(libs.bouncycastle.bcpkix)    // Key exchange for SFTP
    implementation(libs.gson)                  // JSON serialization
    implementation(libs.androidx.security.crypto) // Encrypted credentials
    implementation(libs.google.api.client.android) // Google API
    implementation(libs.google.api.services.drive) // Google Drive
    implementation(libs.play.services.auth)    // Google Sign-In

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}