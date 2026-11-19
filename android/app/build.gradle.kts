import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Версия берётся из android/version.properties (version=1.2.3).
// versionCode считается из неё автоматически: 1.2.3 -> 10203.
val appVersion: String = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("version").trim()
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// Ключ подписи: в CI — из переменных окружения, локально — из ~/.android/lectura-release.properties.
val signingProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".android/lectura-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: signingProps.getProperty(prop)

// Ключ Pixabay (картинки к карточкам): локально — android/local.properties (не коммитится),
// в CI — секрет PIXABAY_API_KEY. Пустой ключ — приложение работает, просто без картинок.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val pixabayKey: String = System.getenv("PIXABAY_API_KEY") ?: localProps.getProperty("PIXABAY_API_KEY", "")

android {
    namespace = "app.lectura"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lectura"
        minSdk = 34
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "PIXABAY_API_KEY", "\"$pixabayKey\"")
    }

    signingConfigs {
        val storePath = signingValue("LECTURA_KEYSTORE_PATH", "storeFile")
        if (storePath != null && File(storePath).exists()) {
            create("release") {
                storeFile = File(storePath)
                storePassword = signingValue("LECTURA_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("LECTURA_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("LECTURA_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Схемы Room коммитятся в репозиторий: по ним проверяются миграции БД между версиями приложения.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
