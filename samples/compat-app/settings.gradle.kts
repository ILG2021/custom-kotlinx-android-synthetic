pluginManagement {
    includeBuild("../../synthetic-gradle-plugin")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "synthetic-compat-example"
include(":app")
