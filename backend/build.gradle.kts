plugins {
  base
  id("com.diffplug.spotless") version "8.11.0"
}

spotless {
  kotlinGradle {
    target("*.gradle.kts", "financial-engine/*.gradle.kts")
    ktfmt("0.59").googleStyle()
  }
  format("repositoryText") {
    target(
      "../*.md",
      "../.gitignore",
      "../.gitattributes",
      "../.editorconfig",
      "../.github/**/*.yml",
      "../.github/**/*.md",
      "../docs/**/*.md",
    )
    trimTrailingWhitespace()
    endWithNewline()
  }
}

tasks.named("check") { dependsOn(":financial-engine:check") }

tasks.named("assemble") { dependsOn(":financial-engine:assemble") }

tasks.wrapper {
  gradleVersion = "9.6.1"
  distributionType = Wrapper.DistributionType.BIN
  distributionSha256Sum = "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
}
