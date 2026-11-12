plugins {
  java
  id("org.springframework.boot")
  id("com.diffplug.spotless")
}

dependencies {
  implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
  implementation(project(":financial-engine"))
  implementation(project(":job-store"))
  implementation(project(":messaging"))
  implementation("org.springframework.boot:spring-boot-starter-webmvc")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
  testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation("org.testcontainers:testcontainers-postgresql")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform { excludeTags("integration") } }

val integrationTest =
  tasks.register<Test>("integrationTest") {
    description = "Runs real PostgreSQL integration tests; requires Docker."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
  }

tasks.check { dependsOn(integrationTest) }

spotless { java { googleJavaFormat("1.28.0") } }
