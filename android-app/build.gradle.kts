// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
// Derive the Android versionCode from the semantic version in
// gradle.properties, so the two can never disagree and nobody has to remember
// to bump an opaque integer. MAJOR*10000 + MINOR*100 + PATCH keeps it
// monotonically increasing as long as minor and patch stay under 100, which
// Play requires for every upload.
val calmsenseVersionName: String =
    providers.gradleProperty("calmsense.versionName").get()

val calmsenseVersionCode: Int = calmsenseVersionName
    .split(".")
    .also { require(it.size == 3) { "calmsense.versionName must be MAJOR.MINOR.PATCH, got '$calmsenseVersionName'" } }
    .map { it.toIntOrNull() ?: error("Non-numeric part in calmsense.versionName: '$calmsenseVersionName'") }
    .also { (_, minor, patch) ->
        require(minor < 100 && patch < 100) {
            "minor and patch must each stay under 100 to keep versionCode increasing, got '$calmsenseVersionName'"
        }
    }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

extra["calmsenseVersionName"] = calmsenseVersionName
extra["calmsenseVersionCode"] = calmsenseVersionCode
