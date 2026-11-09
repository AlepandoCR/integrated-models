rootProject.name = "integrated-models"

dependencyResolutionManagement {
    repositories {
        // pandastic-core publishes to GitHub Packages, which requires a token even to read.
        // mavenLocal is listed first so a developer who has run its publishToMavenLocal builds
        // without credentials; CI resolves from the remote.
        mavenLocal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://maven.pkg.github.com/AlepandoCR/pandastic-core") {
            name = "pandasticCore"
            // Scoped to its own group. Without this Gradle would consult GitHub Packages for
            // every coordinate, and an unauthenticated request there fails the whole resolution
            // rather than falling through — which breaks even paper-api's dynamic version listing.
            content {
                includeGroup("pandastic.studio")
            }
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR")
                    .orElse(providers.environmentVariable("GITHUB_USER"))
                    .orElse(providers.gradleProperty("gpr.user"))
                    .orNull
                password = providers.environmentVariable("GITHUB_TOKEN")
                    .orElse(providers.gradleProperty("gpr.token"))
                    .orNull
            }
        }
    }
}

include(
    "integrated-models-api",
    "integrated-models-internal",
)

// ---------------------------------------------------------------------------
// integrated-models-nms — included by default, excludable by property.
//
// This module applies paperweight-userdev, and paperweight resolves the Folia dev bundle during
// CONFIGURATION of the project it is applied to, not during execution of a task. Gradle configures
// every included project on every invocation, so while this module is included, *any* command on
// this repository — `:integrated-models-api:test` included — needs the ~26 MB dev bundle either in
// the Gradle cache or reachable on the network. There is no way to make that lazy from the outside;
// the only lever is whether the project is in the build at all, which is why the switch lives here
// in settings.gradle.kts rather than in a build file.
//
// Default on, so CI and every normal build produce the real plugin jar with the NMS layer in it.
// A contributor who is offline, firewalled away from repo.papermc.io, or simply does not want to
// pay the remap passes `-PintegratedModels.nms=false`; the api and internal modules do not depend
// on this one, so every test suite and both jars still build. The jar they get is genuinely
// different, so saying so out loud is part of the deal.
// ---------------------------------------------------------------------------
val nmsEnabled = providers.gradleProperty("integratedModels.nms").getOrElse("true").toBoolean()

if (nmsEnabled) {
    include("integrated-models-nms")
} else {
    logger.lifecycle(
        "integrated-models-nms is EXCLUDED (-PintegratedModels.nms=false): paperweight will not " +
            "run and the shaded plugin jar will not contain the NMS layer."
    )
}
