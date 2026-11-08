plugins {
    java
    jacoco
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "io.github.foundry4j"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(27)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("foundry4j-scheduler")
        description.set(
            "Zero-dependency, high-performance task scheduling library for Java 27+, " +
                "built on a hashed timing wheel and virtual threads."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/foundry4j/foundry4j-scheduler")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("tuyucheng777")
                name.set("tuyucheng777")
                email.set("925343663@qq.com")
            }
        }
        scm {
            url.set("https://github.com/foundry4j/foundry4j-scheduler")
            connection.set("scm:git:git://github.com/foundry4j/foundry4j-scheduler.git")
            developerConnection.set("scm:git:ssh://github.com/foundry4j/foundry4j-scheduler.git")
        }
    }
}

// Fail fast with actionable guidance when publishing without credentials,
// instead of the plugin's cryptic "maven-central-build-service" failure.
tasks.matching { it.name == "publishAllPublicationsToMavenCentralRepository" }.configureEach {
    doFirst {
        val missing = listOf(
            "mavenCentralUsername",
            "mavenCentralPassword",
            "signingInMemoryKey",
            "signingInMemoryKeyPassword"
        ).filterNot { providers.gradleProperty(it).isPresent }
        check(missing.isEmpty()) {
            "Missing Gradle properties: ${missing.joinToString()}. " +
                "See 'Publishing to Maven Central' in README.md for setup instructions."
        }
    }
}
