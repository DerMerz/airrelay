import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Store identity. Provisional: the name is still being checked. Changing it here changes
// the installed package only; the Kotlin code package (namespace below) and the JNI
// function names stay as they are.
val appId = "de.merzkevin.airrelay"

// Source code link shown on the licence screen (GPL-3.0 section 6): the public repository.
val sourceUrl = "https://github.com/DerMerz/airrelay"

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// Release signing with the upload key. The keystore lives outside the repository
// (~/Keys/airrelay, copy in iCloud Drive), its password comes from the environment
// (AIRRELAY_UPLOAD_KEYSTORE_PASSWORD, see tools/build_release.sh which reads it from the
// macOS keychain) or from local.properties (git-ignored). Never from a file in the repo.
val uploadStore: File = (System.getenv("AIRRELAY_UPLOAD_KEYSTORE") ?: localProps.getProperty("storeFile"))
    ?.let { File(it) } ?: File(System.getProperty("user.home"), "Keys/airrelay/airrelay-upload.jks")
val uploadPassword: String? = System.getenv("AIRRELAY_UPLOAD_KEYSTORE_PASSWORD") ?: localProps.getProperty("storePassword")
val uploadKeyReady = uploadStore.isFile && !uploadPassword.isNullOrEmpty()
// tools/build_release.sh sets this: a store build must never fall back to the debug key
if (System.getenv("AIRRELAY_REQUIRE_UPLOAD_KEY") == "1" && !uploadKeyReady) {
    throw GradleException("upload key not available: $uploadStore / AIRRELAY_UPLOAD_KEYSTORE_PASSWORD")
}

android {
    namespace = "de.merzkevin.airrelay"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    signingConfigs {
        if (uploadKeyReady) {
            create("release") {
                storeFile = uploadStore
                storePassword = uploadPassword
                keyAlias = localProps.getProperty("keyAlias") ?: "airrelay"
                // PKCS12: key and store share one password
                keyPassword = localProps.getProperty("keyPassword") ?: uploadPassword
            }
        }
    }

    defaultConfig {
        applicationId = appId
        // Fire OS 6 (Android 7.1) and newer; the measured target is Fire OS 7 (API 28)
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")

        ndk {
            // Fire TV Stick Lite is 32-bit only; newer sticks are 64-bit
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // without the upload key the release build is signed with the local debug key, so
            // it can be installed for measurements; never for the store (build_release.sh
            // refuses that)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }
}

// Applies the UxPlay patches (taken over from jqssun/android-airplay-server) to the
// submodule working tree before CMake configures. Idempotent: touched files are reset first.
tasks.register("applyUxplayPatches") {
    val uxplay = file("src/main/cpp/third_party/UxPlay")
    val patchDir = file("src/main/cpp/patches/UxPlay")
    inputs.dir(patchDir)
    doLast {
        fun git(vararg args: String): String {
            val proc = ProcessBuilder("git", "-C", uxplay.path, *args).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        val patches = patchDir.listFiles { f -> f.extension == "patch" }!!.sorted()
        val touched = patches.flatMap { git("apply", "--numstat", it.path).trim().lines() }
            .map { it.substringAfterLast("\t") }.distinct()
        git("checkout", "--", *touched.toTypedArray())
        patches.forEach { git("apply", "--unidiff-zero", it.path) }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

// Blocker: no APK is packaged unless the unit tests are green, including the
// compatibility contract (CompatContractTest) and the decision tables. There is no build
// path around it; GuardsExistTest fails if this block disappears.
listOf("Debug", "Release").forEach { variant ->
    tasks.matching { it.name == "package${variant}" }.configureEach { dependsOn("test${variant}UnitTest") }
}

tasks.withType<Zip>().configureEach {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

dependencies {
    // Amazon Appstore SDK (In-App Purchasing v3): a plain 460 KB jar from Maven Central, no
    // transitive dependencies, no manifest of its own (entries are in AndroidManifest.xml).
    implementation("com.amazon.device:amazon-appstore-sdk:3.0.9")
    testImplementation("junit:junit:4.13.2")
}
