import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val relServerUrl = providers.gradleProperty("REL_SERVER_URL")
    .orElse("https://relifeapp.com")
    .map { it.trimEnd('/') }
val playCloudProjectNumber = providers.gradleProperty("REL_PLAY_CLOUD_PROJECT_NUMBER")
    .orElse("0")
val playChallengeUrl = providers.gradleProperty("REL_PLAY_CHALLENGE_URL")
    .orElse("")

android {
    namespace = "com.relife.mobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.relife.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REL_SERVER_URL", "\"${relServerUrl.get()}\"")
        buildConfigField("String", "REL_PLAY_CLOUD_PROJECT_NUMBER", "\"${playCloudProjectNumber.get()}\"")
        buildConfigField("String", "REL_PLAY_CHALLENGE_URL", "\"${playChallengeUrl.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

val generatedRelAssets = layout.buildDirectory.dir("generated/relWebAssets")
val syncRelWebAssets by tasks.registering(Sync::class) {
    description = "Packages the current ../rel web UI for offline use."
    into(generatedRelAssets)
    from("../../rel/static") { into("static") }
    from("../../rel/templates") {
        include("index.html", "login.html", "register.html")
        into("templates")
    }
}

android.sourceSets.getByName("main").assets.srcDir(generatedRelAssets.get().asFile)
tasks.named("preBuild").configure { dependsOn(syncRelWebAssets) }

dependencies {
    implementation("com.google.android.play:integrity:1.4.0")
    testImplementation("junit:junit:4.13.2")
}
