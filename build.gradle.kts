import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog") version "2.2.1"
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // Gson ships inside the IDE at runtime; compileOnly guarantees compilation without
    // bundling a second copy that could clash with the platform's.
    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("com.google.code.gson:gson:2.10.1")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdeaCommunity("2025.2.6.1")
        // Java PSI (JavaPsiFacade etc.) for the GRACE `psi:` anchor resolver. Compile-time only —
        // plugin.xml intentionally does NOT hard-depend on com.intellij.modules.java, so geai still
        // loads in non-JVM IDEs; the psi: resolver guards its usage and degrades to a clean error.
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

// IntelliJ IDEA 2025.2 runs on JDK 21; pin the toolchain so plugin bytecode targets 21
// regardless of the JDK that launches Gradle. The foojay-resolver-convention applied in
// settings.gradle.kts auto-provisions a matching JDK when one is not installed locally.
kotlin {
    jvmToolchain(21)
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        // Pin the lower compatibility bound; the plugin builds against 2025.2 (build 252). untilBuild
        // is left to the platform plugin's default (current branch) so updates aren't blocked needlessly.
        ideaVersion {
            sinceBuild = "252"
        }

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = version.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

tasks {
    publishPlugin {
        dependsOn(patchChangelog)
    }
}
