plugins { kotlin("jvm") version "2.1.20" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.ow2.asm:asm:9.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20")
}
