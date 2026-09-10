plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktfmt) apply false
}

subprojects {
    apply(plugin = "com.ncorti.ktfmt.gradle")
    extensions.configure<com.ncorti.ktfmt.gradle.KtfmtExtension> { kotlinLangStyle() }
}
