plugins {
    kotlin("js") version "KOTLIN_VERSION"
}

group = "me.user"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    maven("KOTLIN_BOOTSTRAP_REPO")
    maven("KOTLIN_IDE_PLUGIN_DEPENDENCIES_REPO")
    maven("KOTLIN_REPO")
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    js {
        binaries.executable()
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
    }
}
