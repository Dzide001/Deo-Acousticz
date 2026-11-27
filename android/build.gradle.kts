// Top-level build configuration — no code here, only plugin declarations.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.kotlin.compose)      apply false
}

val prepareClfFixtures by tasks.registering(Exec::class) {
    val repo=rootProject.projectDir.parentFile
    inputs.file(repo.resolve("validation/fixture-permissions/generate.py"))
    for(pkg in listOf("cf1-reference","clf-handedness","clf-magnitude")) {
        inputs.file(repo.resolve("validation/$pkg/provenance.json"))
        inputs.files(fileTree(repo.resolve("validation/$pkg/fixtures")) { include("*.tab") })
    }
    outputs.files(
        repo.resolve("validation/cf1-reference/fixtures/SyntheticActive.CF1"),
        repo.resolve("validation/cf1-reference/fixtures/SyntheticPassive.CF1"),
        repo.resolve("validation/cf1-reference/fixtures/SyntheticPowered.CF1"),
        repo.resolve("validation/clf-handedness/fixtures/SyntheticAsymmetric.CF2"))
    commandLine("python3",repo.resolve("validation/fixture-permissions/generate.py").absolutePath,"--prepare")
}
