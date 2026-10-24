plugins {
    alias(libs.plugins.android.library)
}

import org.gradle.api.tasks.PathSensitivity
import java.util.Properties

abstract class BuildFfmpegTask : DefaultTask() {
    @get:Input
    abstract val ndkPath: Property<String>

    @get:Input
    abstract val cmakePath: Property<String>

    @get:Input
    abstract val abis: Property<String>

    @get:Input
    abstract val jobs: Property<Int>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildInputs: ConfigurableFileCollection

    @get:Internal
    abstract val nativeRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val installDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val configDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val downloadsDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        project.exec {
            workingDir = rootProject.projectDir
            commandLine(
                "bash",
                rootProject.file("scripts/build-ffmpeg-android.sh").absolutePath,
                "--ndk", ndkPath.get(),
                "--cmake", cmakePath.get(),
                "--output", nativeRoot.get().asFile.absolutePath,
                "--abis", abis.get(),
                "--jobs", jobs.get().toString(),
            )
        }
    }
}

val localSdk = sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    .firstOrNull { !it.isNullOrBlank() }
    ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir")
    }
    ?: error("ANDROID_HOME or sdk.dir is required for the native FFmpeg build")
val nativeBuildRoot = layout.buildDirectory.dir("ffmpeg")
val buildFfmpeg = tasks.register<BuildFfmpegTask>("buildFfmpegNative") {
    ndkPath.set(file("$localSdk/ndk/28.2.13676358").absolutePath)
    cmakePath.set(file("$localSdk/cmake/3.31.6/bin/cmake").absolutePath)
    abis.set("arm64-v8a,x86_64")
    jobs.set((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8))
    nativeRoot.set(nativeBuildRoot)
    installDirectory.set(layout.buildDirectory.dir("ffmpeg/install"))
    configDirectory.set(layout.buildDirectory.dir("ffmpeg/config"))
    jniLibsDirectory.set(layout.buildDirectory.dir("ffmpeg/jniLibs"))
    downloadsDirectory.set(layout.buildDirectory.dir("ffmpeg/downloads"))
    buildInputs.from(
        rootProject.file("scripts/build-ffmpeg-android.sh"),
        rootProject.file("scripts/ffmpeg-components.sh"),
        rootProject.file("third_party/native-dependencies.lock.json"),
        rootProject.file("third_party/patches/ffmpeg-n8.1.3-openapv-1.1.3.patch"),
    )
}

android {
    namespace = "com.ccplayer.media.ffmpeg"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Wpedantic")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DFFMPEG_INSTALL_ROOT=${nativeBuildRoot.get().asFile.absolutePath}/install",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure { dependsOn(buildFfmpeg) }

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildFfmpeg,
            BuildFfmpegTask::jniLibsDirectory,
        )
    }
}

dependencies {
    api(project(":core:media"))
    implementation(project(":media:android"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
