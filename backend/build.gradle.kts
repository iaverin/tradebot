import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedQueue
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult

plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

data class FailedTest(
    val taskPath: String,
    val testName: String,
    val stackTrace: String,
)

abstract class FailedTestsReportService :
    BuildService<BuildServiceParameters.None>,
    AutoCloseable {

    private val failedTests = ConcurrentLinkedQueue<FailedTest>()

    fun record(taskPath: String, descriptor: TestDescriptor, result: TestResult) {
        val stackTrace = result.exceptions.joinToString("\n") { exception ->
            StringWriter().also { writer ->
                exception.printStackTrace(PrintWriter(writer))
            }.toString().trimEnd()
        }

        failedTests.add(
            FailedTest(
                taskPath = taskPath,
                testName = listOfNotNull(descriptor.className, descriptor.name)
                    .joinToString(" > "),
                stackTrace = stackTrace,
            ),
        )
    }

    override fun close() {
        if (failedTests.isEmpty()) {
            return
        }

        val report = buildString {
            appendLine()
            appendLine("=".repeat(80))
            appendLine("FAILED TESTS (${failedTests.size})")
            appendLine("=".repeat(80))

            failedTests.forEachIndexed { index, failure ->
                if (index > 0) {
                    appendLine("-".repeat(80))
                }
                appendLine("${failure.taskPath}: ${failure.testName}")
                if (failure.stackTrace.isNotEmpty()) {
                    appendLine(failure.stackTrace)
                }
            }

            append("=".repeat(80))
        }

        println(report)
    }
}

val failedTestsReport = gradle.sharedServices.registerIfAbsent(
    "failedTestsReport",
    FailedTestsReportService::class,
) {}

group = "com.example"
version = "1.0.0"
description = "Authentication backend with Spring Boot"

java {
    sourceCompatibility = JavaVersion.VERSION_23
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

// Change build directory to .build
layout.buildDirectory.set(file(".build"))

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Web
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Spring Security
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Spring Data JPA
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // PostgreSQL Database
    implementation("org.postgresql:postgresql")

    // Temporal SDK for workflow orchestration
    implementation("io.temporal:temporal-sdk:1.25.2")

    // Spring Retry for fetcher retry logic
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")

    // Apache HttpClient for HTTP calls with proxy support
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
    implementation("org.apache.httpcomponents.core5:httpcore5:5.2.5")
    implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.2.5")

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Dotenv for .env file support
    implementation("me.paulschwarz:spring-dotenv:4.0.0")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    implementation("io.jsonwebtoken:jjwt-impl:0.12.6")
    implementation("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
    testCompileOnly("org.projectlombok:lombok:1.18.36")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.36")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.temporal:temporal-testing:1.25.2")

    // Testcontainers (requires Docker)
    testImplementation("org.testcontainers:testcontainers:2.0.4")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.4")
    testImplementation("org.testcontainers:junit-jupiter")

    // Spring WebSocket
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Tyrus WebSocket container (used by StandardWebSocketClient for proxy support)
    implementation("org.glassfish.tyrus.bundles:tyrus-standalone-client:2.1.5")

    // web3j for EIP-712 signing
    implementation("org.web3j:core:4.14.0")

    implementation("me.paulschwarz:spring-dotenv:4.0.0")

    implementation("org.jspecify:jspecify:1.0.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("manual")
    }
    outputs.upToDateWhen { false }

    // Testcontainers on Linux: explicit Docker socket (no Docker Desktop)
    // environment("DOCKER_HOST", "unix:///var/run/docker.sock")

    // Show test results in console
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true

        // Show standard output and error in console
        showStandardStreams = false

        // Show summary after test execution
        afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
            if (desc.parent == null) { // Only print for the root suite
                println("\n" + "=".repeat(80))
                println("Test Results: ${result.resultType}")
                println("Tests run: ${result.testCount}, " +
                        "Passed: ${result.successfulTestCount}, " +
                        "Failed: ${result.failedTestCount}, " +
                        "Skipped: ${result.skippedTestCount}")
                println("Duration: ${(result.endTime - result.startTime) / 1000.0}s")
                println("=".repeat(80))
            }
        }))
    }

    // Generate HTML and XML reports
    reports {
        html.required.set(true)
        junitXml.required.set(true)
    }

    // Fail build if no tests are found
    failFast = false

    // Print report location after tests
     doLast {
        println("\nTest report: file://${reports.html.outputLocation.get()}/index.html")
    }
}

tasks.register<Test>("manualTest") {
    useJUnitPlatform {
        includeTags("manual")
    }
    outputs.upToDateWhen { false }

    @Suppress("UNCHECKED_CAST")
    systemProperties = System.getProperties().filterKeys {
        it.toString().startsWith("proxy.")
    } as Map<String, Any>

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true
    }
}

tasks.withType<Test>().configureEach {
    usesService(failedTestsReport)

    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
        if (result.resultType == TestResult.ResultType.FAILURE) {
            failedTestsReport.get().record(path, descriptor, result)
        }
    }))
}
