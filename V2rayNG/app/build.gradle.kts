plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

// Функция для чтения версии из файла
fun getVersionFromFile(): String {
    val versionFile = file(System.getProperty("user.home") + "/v2rayNG-2.0.9/V2rayNG/version.txt")
    return if (versionFile.exists()) {
        versionFile.readText().trim()
    } else {
        // Значение по умолчанию, если файл не найден
        "0"
    }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.v2rayng.crack"
        minSdk = 24
        targetSdk = 36
        versionCode = 709
        versionName = "b5"
        multiDexEnabled = true

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
	splits {
		abi {
			isEnable = true
			reset()
			include("arm64-v8a") // Только arm64
			isUniversalApk = false // Не создавать universal APK
		}
	}

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        
    }


    flavorDimensions.add("version")
    productFlavors {
        create("beta") {
            dimension = "version"
            applicationIdSuffix = ".beta"
        }
        create("stable") {
            dimension = "version"
            // Без суффикса
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

	applicationVariants.all {
		val variant = this
		val versionNumber = getVersionFromFile()
		val isBeta = variant.productFlavors.any { it.name == "beta" }
		
		// Упрощаем, так как у нас только arm64-v8a
		variant.outputs
			.map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
			.forEach { output ->
				// Всегда arm64-v8a, universal не создается
				val abi = "arm64-v8a"
				
				// Формируем имя файла
				output.outputFileName = if (isBeta) {
					"v2crackNG b${versionNumber} (beta).apk"
				} else {
					"v2crackNG b${versionNumber}.apk"
				}
				
				// Уникальный versionCode для arm64 (используем 2 как в вашей карте)
				output.versionCodeOverride = 6_000_000 + (variant.versionCode * 10) + 2
			}
	}

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }


}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    
    // Keep Android Open
    implementation("com.github.woheller69:FreeDroidWarn:V1.+")
 
}
