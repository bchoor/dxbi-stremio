pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Marquee's trailers: NewPipe's YouTube extractor is published only on JitPack
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.[Tt]eam[Nn]ew[Pp]ipe.*") }
        }
    }
}
rootProject.name = "dxbi-tv"
include(":app")
