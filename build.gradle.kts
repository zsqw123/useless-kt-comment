@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
        }
    }
    tasks.withType<JavaCompile> {
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
}