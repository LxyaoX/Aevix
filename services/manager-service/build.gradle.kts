plugins { alias(libs.plugins.agp.lib) }

android {
    buildFeatures { aidl = true }

    buildTypes { release { isMinifyEnabled = false } }

    namespace = "org.aevix.managerservice"
}

dependencies { api(libs.rikkax.parcelablelist) }
