plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kz.cyberqalqan"
    compileSdk = 34

    defaultConfig {
        applicationId = "kz.cyberqalqan"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "2.5"
    }
    // Тұрақты қолтаңба: әр жинақ бір кілтпен қол қойылады, сондықтан жаңарту «App not installed» бермейді.
    // Кілт пен құпиясөз GitHub-та ЖОҚ: олар android/app/keystore.properties және cq-release.keystore
    // файлдарында (.gitignore), немесе CQ_STORE_PASSWORD / CQ_KEY_PASSWORD орта айнымалыларында.
    val ksProps = java.util.Properties().apply {
        val f = file("keystore.properties"); if (f.exists()) f.inputStream().use { load(it) }
    }
    val ksFile = file(ksProps.getProperty("storeFile") ?: "cq-release.keystore")
    val ksPass = ksProps.getProperty("storePassword") ?: System.getenv("CQ_STORE_PASSWORD")
    val hasKey = ksFile.exists() && !ksPass.isNullOrEmpty()
    signingConfigs {
        if (hasKey) create("release") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = ksProps.getProperty("keyAlias") ?: "cyberqalqan"
            keyPassword = ksProps.getProperty("keyPassword") ?: System.getenv("CQ_KEY_PASSWORD") ?: ksPass
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true       // R8: кодты кішірейту және шатастыру (обфускация)
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Кілт жоқ болса (мысалы, GitHub-тан жаңа клон) — debug кілтімен қол қойылады;
            // мұндай APK бұрын орнатылған CyberQalqan-ды жаңарта алмайды.
            signingConfig = signingConfigs.getByName(if (hasKey) "release" else "debug")
        }
    }
    // Екі нұсқа: «full» — хабарландыруларды оқиды; «lite» — оқымайды (Play Protect-тің алаяқтыққа қарсы
    // бұғаттауына түспейді). Екеуі бір қолтаңбамен, бір applicationId-мен — бірін екіншісіне жаңартуға болады.
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("lite") { dimension = "edition"; versionNameSuffix = "-lite" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
