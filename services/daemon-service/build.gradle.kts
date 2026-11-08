plugins { alias(libs.plugins.agp.lib) }

android {
    buildFeatures { aidl = true }

    buildTypes { release { isMinifyEnabled = false } }

    sourceSets {
        named("main") {
            java.directories.addAll(listOf("src/main/java", "../libxposed/service/src/main"))
            aidl.directories.addAll(listOf("src/main/aidl", "../libxposed/interface/src/main/aidl"))
        }
    }

    aidlPackagedList += "org/aevix/ipc/LoadedModule.aidl"
    namespace = "org.aevix.daemonservice"
}

dependencies {
    compileOnly(libs.androidx.annotation)
    compileOnly(libs.libxposed.annotation)
    compileOnly(projects.hiddenapi.stubs)
}
