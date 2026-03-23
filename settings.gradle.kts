pluginManagement {
	repositories {
		maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
		maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
		gradlePluginPortal()
		mavenCentral()
		maven("https://www.jetbrains.com/intellij-repository/releases")
	}
}

rootProject.name = "github-copilot-premium-quota-monitor-for-ij"