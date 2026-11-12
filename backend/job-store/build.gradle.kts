plugins {
  `java-library`
  id("com.diffplug.spotless")
}

dependencies {
  api(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
  api("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  runtimeOnly("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql")
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.mockito:mockito-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless { java { googleJavaFormat("1.28.0") } }
