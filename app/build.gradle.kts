import java.io.FileInputStream
import java.util.Properties

plugins {
    // Só o AGP: Kotlin e o compilador do Compose vêm embutidos no AGP 9 (built-in Kotlin).
    alias(libs.plugins.android.application)
}

/**
        buildConfigField("String", "GIT_SHA", "\" + gitSha() + \"")
 * aparece na tela "Sobre" e no nome dos artefatos do CI — sem isso é impossível
 * correlacionar o feedback do aparelho com uma compilação específica.
 */
fun gitSha(): String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    out.ifBlank { "desconhecido" }
}.getOrDefault("desconhecido")

/**
 * Assinatura de release opcional via `keystore.properties` (fora do VCS).
 * Se não existir, o build de release cai para a chave de debug e o CI continua verde —
 * o artefato do CI é sempre marcado como *instável* de propósito.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}
val hasReleaseKeystore: Boolean = keystoreProps.getProperty("storeFile") != null

/** Canal do motor (ver [versions] em gradle/libs.versions.toml). Define o artifact e se o
 * app pode pedir ao Gecko para aceitar add-on sem assinatura. */
val geckoChannel: String = libs.versions.geckoviewChannel.get()
val allowUnsignedAddons: Boolean = geckoChannel != "release"

android {
    namespace = "app.mobibrowser"
    // API 37: exigida pelo Compose/Material 3 atuais. targetSdk fica em 35 de propósito —
    // o app não precisa das mudanças de comportamento de 36/37 e o gesture back preditivo
    // continua sob controle do BackHandler do Compose.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mobibrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // AGP 9 removeu resourceConfigurations: o filtro de idioma é androidResources.localeFilters.
        androidResources { localeFilters += listOf("pt-BR", "en") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // O que a UI e o log mostram como "versão do motor": no nightly o artifact é
        // dinâmico (158.+), então rotulamos o canal em vez de fingir um pin exato.
        val geckoVersionLabel = if (geckoChannel == "release") {
            libs.versions.geckoviewRelease.get()
        } else {
            "nightly ${libs.versions.geckoview.get()}"
        }
        buildConfigField("String", "GECKOVIEW_VERSION", "\"" + geckoVersionLabel + "\"")
        buildConfigField("String", "GECKOVIEW_CHANNEL", "\"" + geckoChannel + "\"")
        buildConfigField("boolean", "MOBI_ALLOW_UNSIGNED_ADDONS", allowUnsignedAddons.toString())
        // Canal da Chrome Web Store usado no endpoint de download de CRX.
        buildConfigField("String", "CWS_PRODVERSION", "\"139.0.0.0\"")
        buildConfigField("String", "GIT_SHA", "\" + gitSha() + \"")
        buildConfigField("boolean", "MOBI_DEBUG_LOGS", "true")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }

        // Canal "unstable": é o que o CI publica como artefato instável.
        // Assinatura de debug (instala lado a lado com outras variantes), logcat ligado,
        // sem minificação para facilitar o feedback pós-instalação no aparelho.
        create("unstable") {
            applicationIdSuffix = ".unstable"
            versionNameSuffix = "-unstable"
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "MOBI_DEBUG_LOGS", "true")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // ABI splits: APKs menores por aparelho + universal para instalar em qualquer um.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Os pacotes .xpi temporários e os assets da extensão-ponte não devem ser recomprimidos
    // (o parser de ZIP/CRX lê offsets diretos do arquivo).
    androidResources {
        noCompress += listOf("xpi", "crx", "js", "json", "css")
    }

    // Rastreabilidade do artefato: o nome do arquivo no CI/Release leva o SHA (gitSha()
    // abaixo) e a tela Sobre mostra o mesmo valor — evitar APIs internas de variant/output
    // aqui de propósito, elas mudam entre versões do AGP.

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // CI de artefato instável: não falha por warnings de tradução/alpha.
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

// A extensão `kotlin` aqui é a que o AGP registra (built-in Kotlin) — mesma DSL
// `kotlin.compilerOptions {}` recomendada na migração de kotlinOptions.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // `ListProperty`, portanto `addAll` (o operador `+=` não existe aqui e o
        // script do Gradle não compila com ele).
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

dependencies {
    if (geckoChannel == "release") {
        implementation(libs.geckoview.release)
    } else {
        implementation(libs.geckoview.nightly)
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
