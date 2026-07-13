plugins {
  alias(libs.plugins.hilt) apply false
  alias(libs.plugins.ksp) apply false
}

tasks.register<Delete>("clean") {
  delete(layout.buildDirectory)
}
