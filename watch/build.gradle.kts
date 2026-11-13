import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The same keystore as `:app`, read the same way — see app/build.gradle.kts for why it is never
// checked in. Both halves must be signed with one key: the Wear Data Layer only delivers between
// applications that share a package name *and* a signature, so a watch app signed with a
// throwaway debug key is a watch app that receives nothing, with no error anywhere to say so.
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
    namespace = "ch.heuscher.gentleconnect.watch"
    compileSdk = 36

    defaultConfig {
        // Deliberately identical to the phone app's. See settings.gradle.kts.
        applicationId = "ch.heuscher.gentleconnect"
        // Wear OS 3 and later. Below that the watch has no Compose worth the name, and her watch
        // is not that old.
        minSdk = 30
        targetSdk = 36
        // Offset from the phone's, because Play requires the two halves of one application to
        // carry different version codes even though they carry the same name. Both are worked
        // out in the root build file, from one count, so the two halves can never drift apart.
        versionCode = rootProject.extra["watchVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    // The person, the tick, the cross and the telephone on this screen. Extended rather than the
    // core set because `PhoneAndroid` is not in the core set.
    implementation(libs.androidx.material.icons.extended)
    // Wear's own Material, not the phone's: a round screen needs a list that curves at the top
    // and bottom, and buttons sized for a wrist rather than a thumb on a slab of glass.
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
