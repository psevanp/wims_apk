plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.rosaparks.wims"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.rosaparks.wims"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Clé de signature fixe : sans elle, chaque compilation GitHub produit une signature
    // différente et Android refuse de mettre à jour l'application déjà installée.
    signingConfigs {
        getByName("debug") {
            storeFile = file("wims-debug.p12")
            storePassword = "wimsrp2026"
            keyAlias = "wims"
            keyPassword = "wimsrp2026"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.0.0")
}
