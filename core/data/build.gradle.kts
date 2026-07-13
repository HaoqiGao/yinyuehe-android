plugins {
  id("yinyuehe.android.library")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
}

android {
  namespace = "app.yinyuehe.core.data"
}

dependencies {
  implementation(project(":core:common"))
  implementation(libs.hilt.android)
  implementation(libs.kotlinx.coroutines.core)
  ksp(libs.hilt.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.robolectric)
}
