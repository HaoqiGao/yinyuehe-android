package app.yinyuehe.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureAndroid(extension: CommonExtension<*, *, *, *, *, *>) {
  extension.apply {
    compileSdk = 36
    defaultConfig {
      minSdk = 26
      testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
      sourceCompatibility = JavaVersion.VERSION_17
      targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
      abortOnError = true
      warningsAsErrors = true
    }
  }
  tasks.withType(KotlinCompile::class.java).configureEach {
    kotlinOptions.jvmTarget = "17"
  }
}
