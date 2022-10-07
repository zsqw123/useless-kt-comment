@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    kotlin("jvm")
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.similarity)
    implementation(libs.compilerEmbeddable)
    implementation(libs.coroutine)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

kotlin {
    explicitApi()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.shadowJar {
    manifest {
        attributes("Main-Class" to "com.zsu.MainKt")
    }
}