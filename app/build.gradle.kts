import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cihmine.parkingimpact"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cihmine.parkingimpact"
        // Pleos Connect 에뮬레이터 이미지가 API 34
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // 시연 시나리오(demo/scenarios)를 앱에 그대로 넣는다 — 저장소의 JSON 과 앱이 쓰는 입력이 같다.
    sourceSets["main"].assets.srcDir("../demo")

    signingConfigs {
        // 서명 키는 저장소 밖에 둔다(개발자 키 공용). 위치는 -Pparkingimpact.signing=경로 로 바꿀 수 있고,
        // 파일이 없으면 release 는 서명 없이 만들어진다(설치용 APK 아님).
        val props = file(
            providers.gradleProperty("parkingimpact.signing")
                .getOrElse("${System.getProperty("user.home")}/.android-keys/carvoicegame.properties"),
        )
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use(::load) }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    // Pleos Connect 음성 안내 SDK. Pleos 음성 서비스가 없는 기기에서는 안드로이드 기본 TTS 를 쓴다.
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
