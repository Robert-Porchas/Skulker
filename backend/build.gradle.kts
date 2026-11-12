plugins {
  base
  id("com.diffplug.spotless") version "8.11.0"
  id("org.springframework.boot") version "4.1.2" apply false
}

subprojects {
  group = "dev.skulker"
  version = "0.1.0-SNAPSHOT"
  plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
      toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }
    tasks.withType<JavaCompile>().configureEach {
      options.encoding = "UTF-8"
      options.release = 21
      options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
      testLogging { events("failed", "skipped") }
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
      isPreserveFileTimestamps = false
      isReproducibleFileOrder = true
    }
  }
}

spotless {
  kotlinGradle {
    target("*.gradle.kts", "*/build.gradle.kts")
    ktfmt("0.59").googleStyle()
  }
  format("repositoryText") {
    target(
      "../*.md",
      "../.gitignore",
      "../.gitattributes",
      "../.editorconfig",
      "../compose.yml",
      "../.github/**/*.yml",
      "../.github/**/*.md",
      "../docs/**/*.md",
    )
    trimTrailingWhitespace()
    endWithNewline()
  }
}

tasks.named("check") {
  dependsOn(
    ":financial-engine:check",
    ":job-store:check",
    ":api-service:check",
    ":shared-domain:check",
    ":messaging:check",
    ":worker-service:check",
  )
}

tasks.named("assemble") {
  dependsOn(
    ":financial-engine:assemble",
    ":job-store:assemble",
    ":api-service:assemble",
    ":shared-domain:assemble",
    ":messaging:assemble",
    ":worker-service:assemble",
  )
}

tasks.wrapper {
  gradleVersion = "9.6.1"
  distributionType = Wrapper.DistributionType.BIN
  distributionSha256Sum = "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
}
