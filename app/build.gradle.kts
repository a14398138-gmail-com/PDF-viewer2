plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.aistudio.pdfviewer.vtwk"
    minSdk = 24
    targetSdk = 36
    versionCode = 7
    versionName = "1.2.4"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val llamaCppDir = System.getenv("LLAMA_CPP_DIR")?.let { file(it) }
        ?: listOf(
            file("../../../llama.cpp"),
            file("../../llama.cpp"),
            file("../llama.cpp"),
            file("llama.cpp")
        ).firstOrNull { it.exists() }

    // PLaMo translation native engine (llama.cpp) enabled when llama.cpp source is available.
    if (llamaCppDir != null && llamaCppDir.exists()) {
      ndk {
        abiFilters += listOf("arm64-v8a")
      }
      externalNativeBuild {
        cmake {
          arguments += "-DLLAMA_CPP_DIR=${llamaCppDir.absolutePath}"
          val kleidiDir = file("../../../.tools/kleidiai-src/kleidiai-v1.32.0")
          if (kleidiDir.exists()) {
            arguments += "-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=${kleidiDir.absolutePath}"
          }
          arguments += "-DCMAKE_BUILD_TYPE=Release"
        }
      }
    }
  }

  val llamaCppSource = System.getenv("LLAMA_CPP_DIR")?.let { file(it) }
      ?: listOf(
          file("../../../llama.cpp"),
          file("../../llama.cpp"),
          file("../llama.cpp"),
          file("llama.cpp")
      ).firstOrNull { it.exists() }

  if (llamaCppSource != null && llamaCppSource.exists()) {
    externalNativeBuild {
      cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
      }
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  bundle {
    language {
      enableSplit = true
    }
    density {
      enableSplit = true
    }
    abi {
      enableSplit = true
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

dependencies {
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.mlkit.text.recognition)
  implementation(libs.mlkit.text.recognition.japanese)
  implementation(libs.pdfbox.android)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
