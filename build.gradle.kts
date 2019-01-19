val kotlinPluginVersion: String by project

plugins {
    kotlin("jvm") apply false
    id("org.jetbrains.dokka") apply false

    id("io.gitlab.arturbosch.detekt") apply false

    id("com.github.ben-manes.versions")
    id("org.jetbrains.gradle.plugin.idea-ext")
}

tasks.named<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>("dependencyUpdates") {
    resolutionStrategy {
        componentSelection {
            all {
                val isNonFinalVersion = listOf("alpha", "beta", "rc", "cr", "m").any { qualifier ->
                    candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-]*"))
                }

                if (isNonFinalVersion) reject("Release candidate")
            }
        }
    }
}

subprojects {
    buildscript {
        repositories {
            jcenter()
        }
    }

    //if (name == "reporter-web-app") return@subprojects

    // Apply core plugins.
    apply(plugin = "jacoco")

    // Apply third-party plugins.
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    /*sourceSets.create("funTest") {
        kotlin.srcDirs("src/funTest/kotlin")
    }*/

    repositories {
        jcenter()
    }

    /*dependencies {
        detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:$detektPluginVersion")
    }*/

    /*plugins.withType(JavaLibraryPlugin) {
        dependencies {
            api("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

            testImplementation("io.kotlintest:kotlintest-core:$kotlintestVersion")
            testImplementation("io.kotlintest:kotlintest-assertions:$kotlintestVersion")
            testImplementation("io.kotlintest:kotlintest-runner-junit5:$kotlintestVersion")
            testImplementation(project(":test-utils"))

            funTestImplementation(sourceSets.main.output)
            funTestImplementation(sourceSets.test.output)
            funTestImplementation(configurations.testImplementation)
            funTestRuntime(configurations.testRuntime)
        }
    }*/

    configurations.all {
        resolutionStrategy {
            // Ensure that all transitive versions of "kotlin-reflect" match our version of "kotlin-stdlib".
            force("org.jetbrains.kotlin:kotlin-reflect:$kotlinPluginVersion")
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            allWarningsAsErrors = true
            jvmTarget = "1.8"
            apiVersion = "1.3"
        }
    }

    /*detekt {
        // Align the detekt core and plugin versions.
        toolVersion = detektPluginVersion

        config = files("../detekt.yml")
        input = files("src/main/kotlin", "src/test/kotlin", "src/funTest/kotlin")
    }*/

    tasks.register<Test>("funTest") {
        description = "Runs the functional tests."
        group = "Verification"

        //classpath = sourceSets.funTest.runtimeClasspath
        //testClassesDirs = sourceSets.funTest.output.classesDirs
    }

    // Enable JaCoCo only if a JacocoReport task is in the graph as JaCoCo
    // is using "append = true" which disables Gradle's build cache.
    gradle.taskGraph.whenReady {
        val enabled = allTasks.any { it is JacocoReport }

        tasks.withType<Test>().configureEach {
            jacoco.enabled = enabled

            systemProperties = listOf("kotlintest.tags.include", "kotlintest.tags.exclude").associateWith {
                System.getProperty(it)
            }

            testLogging {
                events("started", "passed", "skipped", "failed")
                exceptionFormat("full")
            }

            useJUnitPlatform()
        }
    }

    /*tasks {
        "jacocoTestReport" {
            reports {
                // Enable XML in addition to HTML for CI integration.
                xml.enabled(true)
            }
        }
    }*/

    tasks.register<JacocoReport>("jacocoFunTestReport") {
        description = "Generates code coverage report for the funTest task."
        group = "Reporting"

        executionData(funTest)
        sourceSets(sourceSets.main)

        reports {
            // Enable XML in addition to HTML for CI integration.
            xml.enabled(true)
        }
    }

    tasks.register("jacocoReport") {
        description = "Generates code coverage reports for all test tasks."
        group = "Reporting"

        dependsOn(tasks.withType(JacocoReport))
    }

    check.dependsOn(funTest)

    tasks.register<org.jetbrains.dokka.gradle.DokkaTask>("dokkaJavadoc") {
        outputFormat = "javadoc"
        outputDirectory = "$buildDir/javadoc"
    }

    tasks.register<Jar>("sourcesJar") {
        classifier = "sources"
        from(sourceSets.main.allSource)
    }

    tasks.register<Jar>("dokkaJar") {
        dwependsOn(dokka)
        classifier = "dokka"
        from(dokka.outputDirectory)
    }

    tasks.register<Jar>("javadocJar") {
        dependsOn(dokkaJavadoc)
        classifier = "javadoc"
        from(dokkaJavadoc.outputDirectory)
    }

    artifacts {
        archives(sourcesJar)
        archives(dokkaJar)
        archives(javadocJar)
    }
}

tasks.register<Exec>("checkCopyright") {
    description = "Checks for HERE Copyright headers in Kotlin files."
    group = "Verification"

    commandLine("git", "grep", "-EL", "Copyright.+HERE", "*.kt")
    ignoreExitValue = true
    standardOutput = ByteArrayOutputStream()

    doLast {
        val output = standardOutput.toString().trim()
        if (output.isNotEmpty()) {
            throw new GradleException("Please add copyright statements to the following Kotlin files:\n$output")
        }
    }
}

tasks.register("check") {
    description = "Runs all checks."
    group = "Verification"

    dependsOn(checkCopyright)
}
