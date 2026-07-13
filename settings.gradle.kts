pluginManagement {
  includeBuild("build-logic")
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "yinyuehe-android"

include(":core:common")
include(":core:data")
include(":core:designsystem")
include(":core:player")
include(":core:testing")
include(":feature:library")
