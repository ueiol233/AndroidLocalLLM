pluginManagement {
    repositories {
        // 阿里云对部分新构件（如 KSP 插件）返回 502 且 Gradle 遇 5xx 会中止解析，
        // 故 Maven Central 优先，阿里云作为国内加速后备。
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AndroidLocalLLM"
include(":app")