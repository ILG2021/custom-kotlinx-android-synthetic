plugins {
    kotlin("jvm") version "2.1.20"
    `java-gradle-plugin`
}
group = "com.github.kevin58cmd"
version = "0.1.0"
repositories { google(); mavenCentral() }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":generator"))
    compileOnly("com.android.tools.build:gradle-api:8.7.2")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}
gradlePlugin {
    plugins {
        create("syntheticCompat") {
            id = "com.github.kevin58cmd.synthetic-compat"
            implementationClass = "com.github.kevin58cmd.synthetic.gradle.SyntheticPlugin"
            displayName = "Kotlin synthetic compatibility"
            description = "Generate Kotlin properties compatible with kotlinx.android.synthetic imports"
        }
    }
}
tasks.test {
    systemProperty("synthetic.pluginRoot", projectDir.absolutePath)
    maxHeapSize = "768m"
}
