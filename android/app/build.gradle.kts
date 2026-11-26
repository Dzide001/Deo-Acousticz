plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace   = "com.droidacoustic.pro"
    compileSdk  = 35

    defaultConfig {
        applicationId  = "com.droidacoustic.pro"
        minSdk         = 29       // Android 10 — required for Vulkan 1.1 + good GPU support
        targetSdk      = 35
        versionCode    = 1
        versionName    = "0.1.0-phase0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ─── NDK config ──────────────────────────────────────────────────────
        ndk {
            // arm64-v8a covers the OnePlus Pad 3 and all modern Android tablets.
            // Add x86_64 only if you need the emulator.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-fexceptions",   // Required by STL and engine exceptions
                    "-frtti",         // Required by some Filament internals
                    "-DANDROID"
                )
                arguments(
                    // Pass repo root paths into CMake so the engine sources can
                    // be located from the android sub-project.
                    "-DENGINE_DIR=${project.rootDir.parent}/engine",
                    "-DSHADERS_DIR=${project.rootDir.parent}/shaders"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            externalNativeBuild {
                cmake {
                    cppFlags += "-DDAC_DEBUG=1"   // Enables Vulkan validation layers
                }
            }
        }
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Java 17 is the baseline AGP 8.x and the Compose compiler are built against;
    // 11 was leaving desugaring and API surface on the table for no benefit. CI
    // already provisions JDK 17.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // ─── NDK version — pin to known working version ───────────────────────────
    ndkVersion = "27.0.12077973"

    // android.util.Log is a stub on the JVM and throws by default. The acoustics
    // are plain maths and have no business needing a device to be exercised.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared-core"))
    // ─── AndroidX core ───────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // ─── Compose ─────────────────────────────────────────────────────────────
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.viewmodel.compose)

    // ─── Filament ────────────────────────────────────────────────────────────
    // filament-android provides the core Engine, Renderer, Scene, etc.
    // filament-utils provides ModelViewer, KTX loader helpers.
    implementation(libs.filament.android)
    implementation(libs.filament.utils)

    // ─── Coroutines ──────────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)

    // ─── Debug tooling ───────────────────────────────────────────────────────
    debugImplementation(libs.compose.ui.tooling)

    // ─── Testing ─────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar's org.json is a throwing stub; the persistence layer is built
    // on JSONObject, so unit tests need a real implementation on the classpath.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// Benchmark selection changes test execution even when compiled test classes are unchanged.
tasks.withType<Test>().configureEach {
    inputs.property("diffuseBenchmarkHz", providers.environmentVariable("ACOUSTIC_DIFFUSE_BENCH_HZ").orElse("1000"))
    inputs.property("benchmarkExportDirectory", providers.environmentVariable("ACOUSTIC_BENCHMARK_EXPORT_DIR").orElse(""))
}

tasks.withType<Test>().configureEach {
    val validationRoot = project.file("../..")
    val source = providers.environmentVariable("ACOUSTIC_MANUFACTURER_SOURCE_DIR")
        .orElse(validationRoot.resolve("corpus/clf/martin_audio").absolutePath)
    inputs.property("manufacturerSource", source)
    val files = listOf("CLF2_XD12.tab", "Martin Audio-XD12.CF2").map { File(source.get(),it) }
    inputs.files(files.filter { it.exists() })
    inputs.dir(validationRoot.resolve("validation/comparison-import/fixtures"))
    inputs.dir(validationRoot.resolve("validation/public-measurements/imports"))
    inputs.file(validationRoot.resolve("validation/public-measurements/fixtures.json"))
    inputs.file(validationRoot.resolve("validation/angular-domains/recovery-fixture.json"))
    inputs.file(validationRoot.resolve("validation/speaker-data/results/reference.json"))
    inputs.file(validationRoot.resolve("validation/speaker-data/reference.py"))
    systemProperty("manufacturerValidationRoot",validationRoot.absolutePath)
    systemProperty("manufacturerValidationSource",source.get())
    val report=layout.buildDirectory.file("reports/speaker-data/${name}.json")
    outputs.file(report)
    systemProperty("manufacturerValidationReport",report.get().asFile.absolutePath)
    inputs.dir(validationRoot.resolve("validation/clf-handedness/fixtures"))
    inputs.files(validationRoot.resolve("validation/clf-handedness/provenance.json"),validationRoot.resolve("validation/clf-handedness/results/reference.json"))
    val handednessReport=layout.buildDirectory.file("reports/clf-handedness/results.json")
    outputs.file(handednessReport)
    systemProperty("externalClfHandednessReport",handednessReport.get().asFile.absolutePath)
    inputs.dir(validationRoot.resolve("validation/clf-magnitude/fixtures"))
    inputs.files(validationRoot.resolve("validation/clf-magnitude/provenance.json"),validationRoot.resolve("validation/clf-magnitude/results/reference.json"))
    val magnitudeReport=layout.buildDirectory.file("reports/clf-magnitude/results.json")
    outputs.file(magnitudeReport)
    systemProperty("externalClfMagnitudeReport",magnitudeReport.get().asFile.absolutePath)
    inputs.dir(validationRoot.resolve("validation/cf1-reference/fixtures"))
    inputs.files(validationRoot.resolve("validation/cf1-reference/reference.py"),validationRoot.resolve("validation/cf1-reference/provenance.json"),validationRoot.resolve("validation/cf1-reference/results/reference.json"))
    val cf1Report=layout.buildDirectory.file("reports/cf1-reference/results.json")
    outputs.file(cf1Report)
    systemProperty("cf1ReferenceReport",cf1Report.get().asFile.absolutePath)
    inputs.dir(validationRoot.resolve("validation/powered-reference/fixtures"))
    val poweredReport=layout.buildDirectory.file("reports/powered-reference/results.json")
    outputs.file(poweredReport)
    systemProperty("poweredReferenceReport",poweredReport.get().asFile.absolutePath)
}
