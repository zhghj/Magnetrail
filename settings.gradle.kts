pluginManagement {
    repositories {
        // 国内可直连的镜像优先（dl.google.com / plugins.gradle.org 在本机被墙）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://dl.frostwire.com/maven") {
            content { includeGroup("com.frostwire") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // libtorrent 的 52MB AAR 已收编到工程内，避免每次构建都从 dl.frostwire.com 拉
        maven {
            url = uri("libs/maven")
            content {
                includeGroup("com.frostwire")
                includeGroup("com.k2fsa.sherpa-onnx")
            }
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://dl.frostwire.com/maven") {
            content { includeGroup("com.frostwire") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Magnetrail"

include(":shared")
include(":androidApp")
