plugins {
  `java-library`
  id("com.diffplug.spotless")
}

dependencies {
  api(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
  api(project(":shared-domain"))
  api(project(":job-store"))
  api("org.springframework.boot:spring-boot-starter-amqp")
  api("org.springframework.boot:spring-boot-starter-jackson")
  api("io.micrometer:micrometer-core")
}

spotless { java { googleJavaFormat("1.28.0") } }
