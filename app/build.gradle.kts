import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.resproxy"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.resproxy"
        minSdk = 26
        targetSdk = 36
        // One build number per commit, so every release installs over the previous one.
        val build = runCatching {
            ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).start()
                .inputStream.bufferedReader().readText().trim().toInt()
        }.getOrDefault(1)
        versionCode = build
        versionName = "0.1.$build"

        externalNativeBuild {
            ndkBuild {
                targets("hev-socks5-tunnel")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload build: sign with the debug key so `assembleRelease` produces an installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

// region libwgbridge.so (userspace WireGuard, Go) — needs Go: `go.dir` in local.properties or GOROOT.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val goDir: String? = localProps.getProperty("go.dir") ?: System.getenv("GOROOT")
val wgSrc = rootProject.file("wgbridge")
val wgOut = layout.buildDirectory.dir("wgbridge/jniLibs")

val buildWgBridge by tasks.registering {
    description = "Cross-compiles wgbridge (wireguard-go) for every ABI"
    inputs.dir(wgSrc)
    outputs.dir(wgOut)
    doLast {
        val go = goDir?.let { File(it, "bin/go" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "") }
        if (go == null || !go.exists()) {
            logger.warn("Go not found (set go.dir in local.properties): building without WireGuard chaining")
            return@doLast
        }
        val ndkBin = android.ndkDirectory.resolve("toolchains/llvm/prebuilt").listFiles()!!.first().resolve("bin")
        val ext = if (System.getProperty("os.name").startsWith("Windows")) ".cmd" else ""
        val targets = mapOf(
            "arm64-v8a" to ("arm64" to "aarch64-linux-android26-clang"),
            "armeabi-v7a" to ("arm" to "armv7a-linux-androideabi26-clang"),
            "x86_64" to ("amd64" to "x86_64-linux-android26-clang"),
            "x86" to ("386" to "i686-linux-android26-clang"),
        )
        for ((abi, t) in targets) {
            val out = wgOut.get().dir(abi).file("libwgbridge.so").asFile
            val pb = ProcessBuilder(
                go.absolutePath, "build", "-trimpath", "-buildmode=c-shared",
                "-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384", "-o", out.absolutePath, ".",
            ).directory(wgSrc).redirectErrorStream(true)
            pb.environment().putAll(
                mapOf("CGO_ENABLED" to "1", "GOOS" to "android", "GOARCH" to t.first, "GOARM" to "7",
                    "CC" to ndkBin.resolve(t.second + ext).absolutePath)
            )
            val proc = pb.start()
            val log = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) throw GradleException("wgbridge build failed for $abi: $log")
            out.parentFile.resolve("libwgbridge.h").delete()
        }
    }
}
android.sourceSets["main"].jniLibs.srcDir(wgOut)
tasks.named("preBuild") { dependsOn(buildWgBridge) }
// endregion

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
