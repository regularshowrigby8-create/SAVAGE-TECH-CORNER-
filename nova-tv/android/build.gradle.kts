// Root build file, shaped after Drjacky/MVVMTemplate (MIT).
// Every plugin a module uses is declared here with `apply false`, then applied
// in the module. Upstream also declares ksp, hilt and three analysis plugins;
// nova-tv imports none of them, so none are carried over.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.pitest) apply false
}
