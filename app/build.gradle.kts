plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-kapt")
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.google.gms.google-services") apply false
}

// Sentinel written into R.string.web_client_id when Firebase is not configured.
val missingWebClientId = "MISSING_WEB_CLIENT_ID"

// Read at configuration time so the login button can use R.string.web_client_id
// without hardcoding it. Missing file keeps CI able to compile.
val googleWebClientId: String =
    file("google-services.json").let { googleServices ->
        if (!googleServices.exists()) {
            missingWebClientId
        } else {
            val block =
                """\{[^{}]*"client_type"\s*:\s*3[^{}]*\}"""
                    .toRegex()
                    .find(googleServices.readText())
                    ?.value
            if (block == null) {
                missingWebClientId
            } else {
                """"client_id"\s*:\s*"([^"]+)""""
                    .toRegex()
                    .find(block)
                    ?.groupValues
                    ?.get(1)
                    ?.takeIf { it.isNotBlank() }
                    ?: missingWebClientId
            }
        }
    }

android {
    namespace = "com.comp90018.flashcards"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.comp90018.flashcards"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        resValue("string", "web_client_id", googleWebClientId)
        resValue(
            "string",
            "social_api_base_url",
            providers.gradleProperty("SOCIAL_API_URL").getOrElse(
                "https://comp90018-mobile-systems-2026-12898337120.australia-southeast2.run.app",
            ),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            // android calls such as Log.w return defaults instead of throwing in unit tests
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room's verifier needs a writable directory for its native SQLite library.
tasks.withType<org.jetbrains.kotlin.gradle.internal.KaptWithoutKotlincTask>().configureEach {
    val sqliteTempDir = layout.buildDirectory.dir("tmp/sqlite/$name")
    kaptProcessJvmArgs.add(sqliteTempDir.map { "-Dorg.sqlite.tmpdir=${it.asFile.absolutePath}" })
    doFirst {
        sqliteTempDir.get().asFile.mkdirs()
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")

    // ViewModel & Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Room
    val roomVersion = "2.8.3"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Nearby Connections (clash mode)
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // Firebase Auth + Firestore + Google sign-in. The Google Services plugin is applied
    // only when app/google-services.json exists, so CI can still compile.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.57.2")
    kapt("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")

    // Background deck sync once a network is available.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    // mocks the nearby result classes, which cannot be built by hand in a unit test
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Material3
    implementation("androidx.compose.material3:material3")
    // Detekt Compose-aware rules
    detektPlugins("io.nlopez.compose.rules:detekt:0.5.8")
}

detekt {
    toolVersion = "2.0.0-alpha.3"
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline.set(file("$rootDir/config/detekt/baseline.xml"))
    buildUponDefaultConfig = true
    autoCorrect = true
    source.setFrom(files("src/main/java", "src/test/java", "src/androidTest/java"))
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    reports {
        html.required.set(true)
        checkstyle.required.set(false)
        sarif.required.set(false)
    }
    jvmTarget.set("17")
}

ktlint {
    version.set("1.3.0")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}

// Local Firebase config is gitignored. Skip the plugin when it is absent.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
