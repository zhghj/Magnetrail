import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                // jlibtorrent AAR: Java bindings + native libs for every ABI
                api("com.frostwire:jlibtorrent-android:2.0.15.3")
                // 离线 ASR 运行时（ONNX Runtime + Kotlin 绑定，收编在 libs/maven）
                api("com.k2fsa.sherpa-onnx:sherpa-onnx:2.0.1")
                // 端侧翻译（模型下载后离线运行，不联网）
                implementation("com.google.mlkit:translate:17.0.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            }
        }
    }
}

android {
    namespace = "app.magnetrail.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
