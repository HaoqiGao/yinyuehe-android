plugins {
  id("yinyuehe.android.library")
  id("yinyuehe.android.compose")
}

android {
  namespace = "app.yinyuehe.core.designsystem"
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(libs.junit)
}
