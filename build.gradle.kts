plugins {
    kotlin("jvm") version libs.versions.kotlin apply false
    kotlin("kapt") version libs.versions.kotlin apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
}

tasks.register<CheckMiuixPurityTask>("checkMiuixPurity") {
    group = "verification"
    description = "Reject Material3 imports in migrated Miuix screens"
}
