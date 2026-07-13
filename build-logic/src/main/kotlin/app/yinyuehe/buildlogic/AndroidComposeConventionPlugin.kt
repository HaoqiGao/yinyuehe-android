package app.yinyuehe.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

class AndroidComposeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
    extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true
  }
}
