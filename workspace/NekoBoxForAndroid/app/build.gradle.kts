@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

setupApp()

val validateBundledSingBoxAssets = tasks.register("validateBundledSingBoxAssets") {
    val assetsDir = layout.projectDirectory.dir("src/main/assets/sing-box")
    inputs.dir(assetsDir)
    doLast {
        val requiredAssets = listOf(
            "geoip.db.xz",
            "geoip.version.txt",
            "geosite.db.xz",
            "geosite.version.txt",
            "throne-ruleset-srslist.h",
            "throne-ruleset.version.txt",
            "itdog-ruleset.json",
            "itdog-ruleset.version.txt",
        )
        val invalidAssets = requiredAssets.filter { name ->
            val asset = assetsDir.file(name).asFile
            !asset.isFile || asset.length() == 0L
        }
        check(invalidAssets.isEmpty()) {
            "Missing or empty bundled sing-box assets: ${invalidAssets.joinToString()}. " +
                "Run buildScript/lib/assets.sh before building."
        }
        val itdogRuleset = assetsDir.file("itdog-ruleset.json").asFile.readText().trim()
        check(itdogRuleset.startsWith("{") && itdogRuleset.endsWith("}")) {
            "Invalid bundled sing-box asset: itdog-ruleset.json"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(validateBundledSingBoxAssets)
}

android {
    lint {
        baseline = file("lint-baseline.xml")
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        resources {
            // JGit loads its message bundles through ResourceBundle at runtime. The shared
            // Android packaging setup excludes org/** resources by default, which otherwise
            // makes authenticated HTTP operations fail before credentials can be submitted.
            excludes.remove("org/**")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    implementation("org.slf4j:slf4j-nop:2.0.18")

    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.2.1")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
