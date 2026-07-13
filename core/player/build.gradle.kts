plugins {
  id("yinyuehe.android.library")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
}

android {
  namespace = "app.yinyuehe.core.player"
}

dependencies {
  api(project(":core:common"))
  api(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.core.ktx)
  implementation(libs.hilt.android)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.guava)
  implementation(libs.media3.common)
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.session)
  ksp(libs.hilt.compiler)

  testImplementation(libs.junit)
}
