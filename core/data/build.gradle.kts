plugins {
  id("yinyuehe.android.library")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.room)
}

android {
  namespace = "app.yinyuehe.core.data"

  sourceSets {
    getByName("androidTest").assets.srcDir("$projectDir/schemas")
  }
}

room {
  schemaDirectory("$projectDir/schemas")
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
  }
  generateProtoTasks {
    all().configureEach {
      builtins {
        create("java") { option("lite") }
        create("kotlin") { option("lite") }
      }
    }
  }
}

configurations.configureEach {
  if (name == "kspPluginClasspath") {
    resolutionStrategy.force(
      "org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1",
      "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1",
      "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1",
      "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1",
    )
  }
}

dependencies {
  api(project(":core:common"))
  api(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.datastore)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.hilt.android)
  implementation(libs.protobuf.kotlin.lite)
  ksp(libs.androidx.room.compiler)
  ksp(libs.hilt.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}
