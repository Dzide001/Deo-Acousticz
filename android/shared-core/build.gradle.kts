plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm { compilations.all { kotlinOptions.jvmTarget = "17" } }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("com.squareup.okio:okio:3.9.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(libs.junit) }
    }
}

tasks.withType<Test>().configureEach {
    inputs.dir(project.file("../../validation/asymmetric-directivity"))
    val report=layout.buildDirectory.file("reports/asymmetric-directivity/results.json")
    outputs.file(report)
    systemProperty("asymmetricDirectivityReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    val fixtures=project.file("../../validation/clf-handedness")
    inputs.dir(fixtures.resolve("fixtures"))
    inputs.files(fixtures.resolve("reference.py"),fixtures.resolve("provenance.json"),fixtures.resolve("results/reference.json"))
    val report=layout.buildDirectory.file("reports/${if(providers.gradleProperty("externalClfValidationRoot").isPresent) "external-clf/" else ""}clf-handedness/results.json")
    outputs.file(report)
    systemProperty("clfHandednessReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    val fixtures=project.file("../../validation/clf-magnitude")
    inputs.dir(fixtures.resolve("fixtures"))
    inputs.files(fixtures.resolve("reference.py"),fixtures.resolve("provenance.json"),fixtures.resolve("results/reference.json"))
    val report=layout.buildDirectory.file("reports/${if(providers.gradleProperty("externalClfValidationRoot").isPresent) "external-clf/" else ""}clf-magnitude/results.json")
    outputs.file(report)
    systemProperty("clfMagnitudeReport",report.get().asFile.absolutePath)
}

// The fixture/reference package is versioned outside platform sources. Tests only read it;
// refreshed engine reports go to build/, never silently replace the reviewed baseline.
tasks.withType<Test>().configureEach {
    val fixtures = project.file("../../validation/numerical")
    inputs.dir(project.file("src"))
    systemProperty("numericalValidationSourceDir", project.file("src").absolutePath)
    inputs.file(fixtures.resolve("reference.py"))
    inputs.file(fixtures.resolve("fixtures.json"))
    inputs.file(fixtures.resolve("results/reference.json"))
    systemProperty("numericalValidationDir", fixtures.absolutePath)
    val report = layout.buildDirectory.file("reports/numerical-validation/engine-jvm.json")
    outputs.file(report)
    systemProperty("numericalValidationReport", report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    inputs.dir(project.file("../../validation/comparison-import/fixtures"))
    val report=layout.buildDirectory.file("reports/comparison-import/results.json")
    outputs.file(report)
    systemProperty("comparisonImportReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    inputs.file(project.file("../../validation/engine-capture/reference.py"))
    inputs.file(project.file("../../validation/engine-capture/results/reference.json"))
    val report=layout.buildDirectory.file("reports/engine-capture/results.json")
    outputs.file(report)
    systemProperty("engineCaptureReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    inputs.file(project.file("../../validation/relative-response/reference.py"))
    inputs.file(project.file("../../validation/relative-response/results/reference.json"))
    val report=layout.buildDirectory.file("reports/relative-response/results.json")
    outputs.file(report)
    systemProperty("relativeResponseReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    inputs.file(project.file("../../validation/broadband-capture/reference.py"))
    inputs.file(project.file("../../validation/broadband-capture/results/reference.json"))
    val report=layout.buildDirectory.file("reports/broadband-capture/results.json")
    outputs.file(report)
    systemProperty("broadbandCaptureReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    inputs.file(project.file("../../validation/relative-broadband/reference.py"))
    inputs.file(project.file("../../validation/relative-broadband/results/reference.json"))
    val report=layout.buildDirectory.file("reports/relative-broadband/results.json")
    outputs.file(report)
    systemProperty("relativeBroadbandReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    inputs.file(project.file("../../validation/source-spectrum/reference.py"))
    inputs.file(project.file("../../validation/source-spectrum/results/reference.json"))
    val report=layout.buildDirectory.file("reports/source-spectrum/results.json")
    outputs.file(report)
    systemProperty("sourceSpectrumReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    val report=layout.buildDirectory.file("reports/validation-workflow/results.json")
    outputs.file(report)
    systemProperty("validationWorkflowReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    inputs.dir(project.file("../../validation/public-measurements"))
    val report=layout.buildDirectory.file("reports/public-measurements/results.json")
    outputs.file(report)
    systemProperty("publicMeasurementReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    val report=layout.buildDirectory.file("reports/angular-support/results.json")
    outputs.file(report)
    systemProperty("angularSupportReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    inputs.file(project.file("../../validation/angular-domains/recovery-fixture.json"))
    val report=layout.buildDirectory.file("reports/angular-domains/results.json")
    outputs.file(report)
    systemProperty("angularDomainReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    val report=layout.buildDirectory.file("reports/receiver-angular-support/results.json")
    outputs.file(report)
    systemProperty("receiverAngularReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    val report=layout.buildDirectory.file("reports/receiver-markers/results.json")
    outputs.file(report)
    systemProperty("receiverMarkerReport",report.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    val report=layout.buildDirectory.file("reports/receiver-framing/results.json")
    outputs.file(report)
    systemProperty("receiverFramingReport",report.get().asFile.absolutePath)
}

// Public synthetic fixtures by default; private external input is explicit and mandatory when selected.
val externalClfRoot=providers.gradleProperty("externalClfValidationRoot").orNull
val clfFixtureRoot=externalClfRoot?.let { File(it) } ?: rootProject.projectDir.parentFile
if(externalClfRoot!=null) require(clfFixtureRoot.isAbsolute && clfFixtureRoot.resolve("external-validation.json").isFile) {
    "Prepare a private external CLF root with validation/fixture-permissions/prepare_external.py first"
}
tasks.withType<Test>().configureEach {
    dependsOn(rootProject.tasks.named("prepareClfFixtures"))
    inputs.property("clfFixtureRoot",clfFixtureRoot.absolutePath)
    for(pkg in listOf("clf-handedness","clf-magnitude","cf1-reference")) {
        inputs.dir(clfFixtureRoot.resolve("validation/$pkg"))
    }
    systemProperty("clfFixtureValidationRoot",clfFixtureRoot.absolutePath)
}
