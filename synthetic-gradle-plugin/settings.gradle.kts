pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }
rootProject.name = "synthetic-gradle-plugin"
include(":generator")
project(":generator").projectDir = file("../generator")
