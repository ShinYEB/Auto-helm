plugins { kotlin("jvm") }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test> { useJUnitPlatform() }
