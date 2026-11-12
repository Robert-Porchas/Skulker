pluginManagement { repositories { gradlePluginPortal() } }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { mavenCentral() }
}

rootProject.name = "skulker"

include("financial-engine")

include("job-store", "api-service")
