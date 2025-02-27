pluginManagement {
    repositories {
        repositories {
            { { kts_kotlin_plugin_repositories } }
        }
    }
    plugins {
        kotlin("multiplatform") version "{{kotlin_plugin_version}}"
        kotlin("android") version "{{kotlin_plugin_version}}"
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android")) {
                useModule("com.android.tools.build:gradle:{{android_gradle_plugin_version}}")
            }
        }
    }

    dependencyResolutionManagement {
        repositories {
            { { kts_kotlin_plugin_repositories } }
        }
    }
}

include(":consumerA")

includeBuild("../producerBuild") {
    dependencySubstitution {
        substitute(module("org.jetbrains.sample:producerA")).using(project(":producerA"))
    }
}
