plugins { kotlin("jvm") version "2.0.21" }
repositories { maven("https://maven-central.storage-download.googleapis.com/maven2") }
val repo = System.getenv("REPO") ?: rootDir.parentFile.parentFile.absolutePath // repository root
val rv = "4.12.2"
val aa = "14-robolectric-10818077-i6"
sourceSets {
  main { java.srcDirs("$repo/android/app/src/main/java", "stubs"); java.exclude("**/PlayerActivity.kt") ; kotlin.srcDirs("$repo/android/app/src/main/java", "stubs"); kotlin.exclude("**/PlayerActivity.kt") }
}
dependencies {
  compileOnly("org.robolectric:android-all:14-robolectric-10818077")
  testImplementation("org.robolectric:robolectric:$rv") { exclude(group = "androidx.test"); exclude(group = "androidx.test.espresso") }
  testImplementation("junit:junit:4.13.2")
  testCompileOnly("org.robolectric:android-all:14-robolectric-10818077")
  testRuntimeOnly("org.robolectric:android-all-instrumented:$aa")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
// Robolectric looks for the Android framework jar in this folder (offline): copy it from the Gradle cache before the tests run
val copyAndroidAll by tasks.registering(Copy::class) {
  from(configurations.testRuntimeClasspath.map { c -> c.filter { it.name.startsWith("android-all-instrumented") } })
  into(System.getenv("ROBOLECTRIC_JARS") ?: layout.buildDirectory.dir("android-all").get().asFile.absolutePath)
}
tasks.test {
  dependsOn(copyAndroidAll)
  systemProperty("robolectric.offline", "true")
  systemProperty("robolectric.resourcesMode", "legacy")
  systemProperty("robolectric.dependency.dir", System.getenv("ROBOLECTRIC_JARS") ?: layout.buildDirectory.dir("android-all").get().asFile.absolutePath)
  systemProperty("repo", repo)
  systemProperty("shots", System.getenv("SHOTS") ?: layout.buildDirectory.dir("shots").get().asFile.absolutePath)
  systemProperty("robolectric.graphicsMode", "NATIVE")
  maxHeapSize = "4g"
  testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
  outputs.upToDateWhen { false }
}
