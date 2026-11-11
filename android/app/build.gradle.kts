plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ------------------------------------------------------------------ nlm-core (Rust)
// Client NotebookLM natif : bibliothèques .so (cargo-ndk) + bindings Kotlin (UniFFI).
val rustDir = rootProject.file("../nlm-core")
val rustJniDir = layout.buildDirectory.dir("generated/rustJniLibs")
val uniffiDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val hostLibExt = if (System.getProperty("os.name").lowercase().contains("mac")) "dylib" else "so"

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compile nlm-core pour Android (arm64-v8a, x86_64)."
    workingDir = rustDir
    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    outputs.dir(rustJniDir)
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-P", "26",
        "-o", rustJniDir.get().asFile.absolutePath, "build", "--release", "--lib",
    )
}

val uniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Génère les bindings Kotlin de nlm-core (UniFFI)."
    workingDir = rustDir
    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    outputs.dir(uniffiDir)
    commandLine(
        "sh", "-c",
        "cargo build --lib && cargo build --features bindgen --bin uniffi-bindgen && " +
            "./target/debug/uniffi-bindgen generate --library target/debug/libnlm_core.$hostLibExt " +
            "--language kotlin --no-format --out-dir '${uniffiDir.get().asFile.absolutePath}'",
    )
}

tasks.named("preBuild") { dependsOn(cargoNdkBuild, uniffiBindings) }

android {
    namespace = "fr.mescours.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.mescours.app"
        minSdk = 26
        targetSdk = 35
        // Fournis par la CI : versionCode = nombre de commits (toujours croissant, pour que
        // chaque APK s'installe par-dessus la précédente), versionName = version publiée.
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0-dev"
    }

    // Signature "release" optionnelle : fournie par des variables d'environnement
    // (secrets GitHub Actions). Sans elles, seule l'APK debug est produite.
    val keystorePath = System.getenv("MESCOURS_KEYSTORE")
    signingConfigs {
        // Clé de debug fixe, versionnée : toutes les APK debug (CI comprise) ont la même
        // signature, donc chaque nouvelle version s'installe par-dessus l'ancienne sans
        // perdre les données. Clé publique : ne pas l'utiliser pour une diffusion large.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MESCOURS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MESCOURS_KEY_ALIAS")
                keyPassword = System.getenv("MESCOURS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sans clé privée fournie, la release est signée avec la clé fixe du dépôt :
            // elle s'installe alors par-dessus les APK debug existantes.
            signingConfig = signingConfigs.getByName(if (keystorePath != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].java.srcDir(uniffiDir.get().asFile)
    sourceSets["main"].jniLibs.srcDir(rustJniDir.get().asFile)

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Requis par les bindings UniFFI de nlm-core.
    implementation("net.java.dev.jna:jna:5.15.0@aar")
}
