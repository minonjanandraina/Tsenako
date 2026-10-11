plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "mg.tsena"
    compileSdk = 35

    defaultConfig {
        applicationId = "mg.tsena"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Clé de signature de production : jamais dans le dépôt, fournie par des variables d'environnement
    // (secrets GitHub dans .github/workflows/release.yml). Sans elle, l'APK de production n'est pas signé.
    val keystore = System.getenv("TSENA_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("production") {
                storeFile = file(keystore)
                storePassword = System.getenv("TSENA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TSENA_KEY_ALIAS")
                keyPassword = System.getenv("TSENA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("production")
            // R8 désactivé pour la 1.0 : à activer après des tests sur téléphone de la version optimisée
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    // Schémas Room versionnés : nécessaires pour écrire les migrations
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
