plugins {
  `kotlin-dsl`
}

group = "app.yinyuehe.buildlogic"

dependencies {
  implementation("com.android.tools.build:gradle:8.12.3")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
  implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.20")
}

gradlePlugin {
  plugins {
    register("androidApplication") {
      id = "yinyuehe.android.application"
      implementationClass = "app.yinyuehe.buildlogic.AndroidApplicationConventionPlugin"
    }
    register("androidLibrary") {
      id = "yinyuehe.android.library"
      implementationClass = "app.yinyuehe.buildlogic.AndroidLibraryConventionPlugin"
    }
    register("androidCompose") {
      id = "yinyuehe.android.compose"
      implementationClass = "app.yinyuehe.buildlogic.AndroidComposeConventionPlugin"
    }
    register("kotlinLibrary") {
      id = "yinyuehe.kotlin.library"
      implementationClass = "app.yinyuehe.buildlogic.KotlinLibraryConventionPlugin"
    }
  }
}
