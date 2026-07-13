plugins {
  id("yinyuehe.android.application")
  id("yinyuehe.android.compose")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
}

android {
  namespace = "app.yinyuehe"
  defaultConfig {
    applicationId = "app.yinyuehe"
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }
  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
  lint {
    disable += setOf(
      "AndroidGradlePluginVersion",
      "GradleDependency",
      "NewerVersionAvailable",
    )
  }
}

dependencies {
  implementation(project(":core:data"))
  implementation(project(":core:designsystem"))
  implementation(project(":core:player"))
  implementation(project(":feature:library"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.hilt.android)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  ksp(libs.hilt.compiler)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
