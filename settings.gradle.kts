rootProject.name = "vuldium"

pluginManagement {
    repositories {
        mavenLocal()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases/") }
        gradlePluginPortal()
    }
}

val targetVersion = System.getProperty("mc.version") ?: "26.1"
val supportFrapi = targetVersion != "26.1"

include("common")
if (supportFrapi) {
    include("frapi")
}
include("fabric")
include("neoforge")
