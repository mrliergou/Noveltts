plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.noveltts"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.noveltts"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // netty（微软语音 SDK 传递依赖）在多个 jar 里各带一份索引/版本清单，
            // 不排除会导致 mergeDebugJavaResource 报重复文件而打包失败。
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
        }
        jniLibs {
            // 本地联调：jniLibs 里放的是原版 APK 提取的微软原生库（不可提交），
            // 与 AAR 自带的同名库冲突时以本地为准。
            pickFirsts += listOf(
                "**/libMicrosoft.CognitiveServices.Speech.core.so",
                "**/libMicrosoft.CognitiveServices.Speech.extension.*.so",
                "**/libMicrosoft.CognitiveServices.Speech.java.bindings.so",
                "**/libc++_shared.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.37.0")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
