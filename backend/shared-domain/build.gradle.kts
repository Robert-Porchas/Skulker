plugins {
  `java-library`
  id("com.diffplug.spotless")
}

dependencies {
  testImplementation(platform("org.junit:junit-bom:5.14.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless { java { googleJavaFormat("1.28.0") } }
