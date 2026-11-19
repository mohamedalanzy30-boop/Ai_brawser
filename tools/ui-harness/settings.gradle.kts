pluginManagement { repositories { maven("https://maven-central.storage-download.googleapis.com/maven2") } 
  resolutionStrategy { eachPlugin { if (requested.id.id == "org.jetbrains.kotlin.jvm") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}") } } }
rootProject.name = "uiharness"
