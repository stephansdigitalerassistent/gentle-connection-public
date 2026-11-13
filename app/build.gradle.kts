import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.google.services)
}

// Signing credentials come from a gitignored keystore.properties locally, or from the
// environment in CI. Never from a checked-in file: this is the upload key, and whoever holds it
// can publish as us.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProperty(fileKey: String, envKey: String): String? =
    keystoreProperties.getProperty(fileKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val uploadStorePath: String? = signingProperty("storeFile", "SIGNING_STORE_FILE")
val hasUploadKey: Boolean = uploadStorePath != null && file(uploadStorePath).exists()

android {
    namespace = "ch.heuscher.gentleconnect"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.heuscher.gentleconnect"
        minSdk = 26
        targetSdk = 36
        // Counted, not typed. See the root build file for why a fixed version code meant she
        // could never be sent a fix.
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadStorePath!!)
                storePassword = signingProperty("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingProperty("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingProperty("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Debug is signed with the same key on purpose. AGP otherwise generates a throwaway
        // debug key per machine, so every CI build produced an APK with a different signature,
        // `adb install -r` failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only way onto
        // the phone was uninstall-then-install — which wipes app-private storage, including the
        // Threema client key. A stable key makes installs upgrades again.
        debug {
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
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
    }

    // Lint is by far the most expensive thing in this build — around 83% of the Gradle step,
    // against under ten seconds to actually compile the app — and most of that was spent checking
    // code that never reaches her phone.
    //
    // Test sources are no longer linted. What lint is worth having for is the shipped app: a
    // missing permission check, a leaked context, a string that cannot be translated. In a test
    // those findings are either harmless or already caught by the test failing, and paying about
    // a minute of every push for them is the wrong trade on a project whose CI is also the only
    // place the app can be built at all.
    //
    // Deliberately left alone: abortOnError. A lint failure still fails the build. Making the
    // slow thing faster is worth doing; making it toothless is not, and "we turned lint down to
    // save a minute" is how a warning about her permissions ends up in a log nobody reads.
    lint {
        ignoreTestSources = true
    }
    packaging {
        jniLibs {
            // The Threema bridge is not a library we link — it is a binary we *exec* as a
            // subprocess, so it has to exist as a real file on disk. Modern AGP defaults to
            // leaving native libs compressed inside the APK and page-mapping them, which makes
            // `<nativeLibraryDir>/arm64/` empty: verified on the device, where the directory
            // exists and contains nothing. ProcessBuilder then fails with "not found" and the
            // whole Threema path is dead with no other symptom.
            //
            // Extracting to the lib directory is also the only placement Android will execute
            // from — W^X forbids exec'ing anything out of the app's data directory.
            useLegacyPackaging = true
        }
        resources {
            // android-mail and android-activation each ship their own copy of these two, and
            // the merger refuses to pick one. Concatenate rather than exclude: they are the
            // legal notices for a dependency that is actually shipped in the APK.
            //
            // Deliberately narrow. A blanket META-INF exclude would also drop
            // META-INF/javamail.default.providers and META-INF/services/javax.mail.Provider,
            // which are what let JavaMail hand back decoded text instead of a raw stream —
            // body extraction would degrade with nothing in the build to say why.
            merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Firebase & GMS
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.auth.ktx)
    implementation(libs.kotlinx.coroutines.play.services)
    // Declared rather than inherited. The telephone service posts its work to Dispatchers.Main,
    // and without this artifact that dispatcher does not exist at runtime — it throws where it is
    // first touched, which is the moment a call arrives. It has always come in behind the
    // lifecycle libraries; a dependency the ringing telephone needs should not be an accident of
    // somebody else's dependency graph.
    implementation(libs.kotlinx.coroutines.android)
    // Trap mitigation: Explicitly force androidx.fragment >= 1.8.6
    implementation(libs.androidx.fragment)

    // Room & Threema backup extraction
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // kapt, NOT annotationProcessor: annotationProcessor only runs over Java sources, so
    // Room would silently generate nothing and AppDatabase_Impl would be missing at runtime.
    // That failure does not show up at compile time — the build goes green and the app
    // crashes on first database access.
    kapt(libs.androidx.room.compiler)
    implementation(libs.zip4j)
    implementation(libs.webrtc.android)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    // The gmail.readonly grant her post is read with. See GmailAuthorizer.
    implementation(libs.play.services.auth)
    // The Data Layer her watch is on the other end of. See WearSyncManager.
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
