plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.spring") version "2.0.21" apply false
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
    group = "dev.autohelm"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}
