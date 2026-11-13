plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.google.services) apply false
}

// One version number for both halves of the application, worked out rather than typed.
//
// It was `versionCode = 1`, hardcoded, and had been since the first commit. Every build therefore
// claimed to be the same version as every other, and that is not cosmetic: Android cannot tell
// which of two builds is the newer one, and any store or updater refuses a second upload of a
// version code it has already seen. So the app could not be delivered by *any* channel that
// updates itself — which is the whole difference between fixing something for her and driving
// over with a cable.
//
// The number of commits is the source. It only ever goes up; it needs no file that CI has to
// write back after a build (which would make every release push a commit, which would trigger a
// release); and it is the same number here as on a runner — provided the runner has the whole
// history, which is why the workflow checks out with `fetch-depth: 0`. A shallow clone would
// quietly say 1.
//
// VERSION_CODE / VERSION_NAME in the environment override it, for a build that has to slot into
// a particular place. A tree with no git at all falls back to the base alone.
val versionCodeBase = 1_000

fun gitCommitCount(): Int = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output.toIntOrNull() ?: 0 else 0
} catch (e: Exception) {
    0
}

val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: (versionCodeBase + gitCommitCount())

extra["appVersionCode"] = appVersionCode
extra["appVersionName"] = System.getenv("VERSION_NAME") ?: "1.0.$appVersionCode"

// The watch half is the same application under the same name, so it needs its own version code —
// two APKs in one listing may not share one. Offset rather than counted separately, so that the
// pair of numbers still says plainly which build of the phone app a watch build belongs to, and
// so the watch's is always the higher of the two, which is the order Play expects.
extra["watchVersionCode"] = appVersionCode + 100_000
