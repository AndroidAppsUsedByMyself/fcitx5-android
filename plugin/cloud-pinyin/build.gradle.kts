plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.cloud_pinyin"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.cloud_pinyin"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            resValue("string", "ipc_service_action", "org.fcitx.fcitx5.android.plugin.SERVICE")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
            resValue(
                "string",
                "ipc_service_action",
                "org.fcitx.fcitx5.android.debug.plugin.SERVICE"
            )
        }
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
    implementation(libs.kotlinx.coroutines)
}
