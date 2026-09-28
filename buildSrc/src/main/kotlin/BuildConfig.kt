import org.gradle.api.Project

object BuildConfig {
    val TARGET_VERSION: String = System.getProperty("mc.version") ?: "26.1"

    val MINECRAFT_VERSION: String = when (TARGET_VERSION) {
        "26.1" -> "26.1"
        "26.2" -> "26.2"
        "26.3" -> "26.3"
        else -> TARGET_VERSION
    }

    val NEOFORGE_VERSION: String = when (TARGET_VERSION) {
        "26.1" -> "26.1.0.19-beta"
        "26.2" -> "26.2.0.88"
        "26.3" -> "26.3.0.12-beta"
        else -> "26.3.0.12-beta"
    }

    val FABRIC_LOADER_VERSION: String = "0.19.3"

    val FABRIC_API_VERSION: String = when (TARGET_VERSION) {
        "26.1" -> "0.145.1+26.1"
        "26.2" -> "0.161.0+26.2"
        "26.3" -> "0.160.5+26.3"
        else -> "0.160.5+26.3"
    }

    val MINECRAFT_DEPENDENCY: String = "$MINECRAFT_VERSION.x"
    val SUPPORT_FRAPI : Boolean = TARGET_VERSION != "26.1"

    // https://semver.org/
    val MOD_VERSION: String = "0.9.3-alpha.1"

    val MINECRAFT_VERSION_SHORT: String = MINECRAFT_VERSION
            .replace("-snapshot-", "s")
            .replace("-pre-", "p")
            .replace("-rc-", "r")

    val RELEASE_TAG: String = "mc$MINECRAFT_VERSION_SHORT-$MOD_VERSION"

    val CURSEFORGE_PROJECT_ID = "394468"
    val MODRINTH_PROJECT_ID = "AANobbMI"

    fun createVersionString(project: Project): String {
        val builder = StringBuilder()

        val isReleaseBuild = project.hasProperty("build.release")
        val buildId = System.getenv("GITHUB_RUN_NUMBER")

        if (isReleaseBuild) {
            builder.append(MOD_VERSION)
        } else {
            builder.append(MOD_VERSION.substringBefore('-'))
            builder.append("-SNAPSHOT")
        }

        builder.append("+mc").append(MINECRAFT_VERSION_SHORT)

        if (!isReleaseBuild) {
            if (buildId != null) {
                builder.append("-build.${buildId}")
            } else {
                builder.append("-local")
            }
        }

        return builder.toString()
    }

    fun calculateGitHash(project: Project): String = try {
        val output = project.providers.exec {
            workingDir(project.projectDir)
            commandLine("git", "rev-parse", "HEAD")
        }
        output.standardOutput.asText.get().trim()
    } catch (_: Throwable) {
        "unknown"
    }

    fun getChangelog(project: Project): String = project.rootProject.file("CHANGELOG.md").readText()
            .split("----------")[1]
            .trim()
            .replace("[ReleaseTag]()", RELEASE_TAG)
            .replace("[MCVersion]()", MINECRAFT_VERSION)
            .replace("[SodiumVersion]()", MOD_VERSION)
}
