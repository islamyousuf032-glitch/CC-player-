plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class BundleThirdPartyNoticesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notices: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenses: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        notices.get().asFile.copyTo(output.resolve("THIRD_PARTY_NOTICES.md"), overwrite = true)
        licenses.get().asFile.copyRecursively(output.resolve("licenses"), overwrite = true)
    }
}

android {
    namespace = "com.ccplayer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ccplayer.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_metrics")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val noticeAssets = tasks.register<BundleThirdPartyNoticesTask>(
            "bundle${variant.name.replaceFirstChar(Char::uppercaseChar)}ThirdPartyNotices",
        ) {
            notices.set(rootProject.file("THIRD_PARTY_NOTICES.md"))
            licenses.set(rootProject.file("licenses"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            noticeAssets,
            BundleThirdPartyNoticesTask::outputDirectory,
        )
    }
}

dependencies {
    implementation(project(":core:media"))
    implementation(project(":media:android"))
    implementation(project(":media:mcraw"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.media3.ui)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
