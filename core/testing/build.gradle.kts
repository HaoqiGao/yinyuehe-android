plugins {
  id("yinyuehe.android.library")
}

android {
  namespace = "app.yinyuehe.core.testing"
}

dependencies {
  api(project(":core:common"))
  api(project(":core:data"))
  api(project(":core:player"))
  api(libs.junit)
  api(libs.kotlinx.coroutines.test)
}
