plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.kigtts.engines.lua"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
tasks.register<Sync>("collectPluginDependencies") {
    from(configurations.getByName("debugRuntimeClasspath"))
    into(layout.buildDirectory.dir("plugin-dependencies"))
}
dependencies {
    compileOnly(project(":plugin-android-api"))
    implementation("org.luaj:luaj-jse:3.0.1")
    testImplementation(project(":plugin-android-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
