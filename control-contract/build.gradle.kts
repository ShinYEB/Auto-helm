plugins {
    kotlin("jvm")
    id("io.spring.dependency-management")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencyManagement { imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.16") } }

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test> { useJUnitPlatform() }
