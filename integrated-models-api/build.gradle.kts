plugins {
    `maven-publish`
}

// This module is interfaces only. It must never gain a BetterModel (kr.toxicity.*)
// dependency: anything that imports the rendering library belongs in
// integrated-models-internal. paper-api, the annotation jars and the JUnit harness all
// come from the root subprojects {} block.

// ---------------------------------------------------------------------------
// pandastic-core — compileOnly AND annotationProcessor, and NOTHING ELSE.
//
// This module carries exactly one @Configurable declaration,
// pandastic.studio.models.api.vehicle.VehicleNotifications, whose generated implementation
// (VehicleNotificationConfig) is part of the PUBLISHED surface: a downstream plugin fills it
// from a file of its own, one per kind of vehicle it ships.
//
// The two scopes are chosen to keep the publication invariant this module rests on intact.
// Neither compileOnly nor annotationProcessor is a published configuration, so:
//
//   * the generated POM still carries NO <dependencies> element at all, which is what makes
//     from(components["java"]) correct here (see the publishing block below), and
//   * a consumer inherits nothing from our coordinate - no compile scope, no runtime scope,
//     nothing transitive. That is non-negotiable: a published api that dragged a runtime
//     library behind it would put a second, possibly different pandastic-core on every
//     consumer's classpath.
//
// The processor still runs, so the generated class is compiled into this module and travels
// in the api jar. What a consumer needs in order to COMPILE against it is pandastic-core on
// their own classpath, which is the stated assumption - this is the house configuration
// library and a plugin using it is assumed to have it. If they do not, the failure is an
// ordinary missing-class compile error in their build, on
// pandastic.studio.core.service.api.config.*, and not a defect here. There is deliberately
// no shading of it into this jar, no runtime dependency added to make it available, and no
// reflective fallback for its absence. VehicleNotifications' own javadoc says all of this
// where a consumer will actually read it.
//
// Verify, do not assume: `gradlew :integrated-models-api:generatePomFileForApiPublication`
// then grep the POM for <dependencies>, and check the jar contains
// pandastic/studio/models/api/vehicle/VehicleNotificationConfig.class.
// ---------------------------------------------------------------------------
dependencies {
    compileOnly(libs.pandastic.core)
    annotationProcessor(libs.pandastic.core)

    // compileOnly does not reach the test classpath, and the api test suite instantiates the
    // generated config to assert that this plugin's own default messages are the declaration's
    // defaults. A test configuration is not published either.
    testImplementation(libs.pandastic.core)
    testImplementation(rootProject.libs.paper.api)
}

// ---------------------------------------------------------------------------
// Publishing — GitHub Packages.
//
// Only this module is published. Consumers are other plugins that compile against the
// model API and get the implementation from the installed integrated-models plugin at
// runtime, so the api jar (plus its sources jar, wired by withSourcesJar() in the root
// build) is the entire publishable surface. The internal module and the shaded plugin
// jar are deliberately not published — nobody should ever compile against an impl.
//
// components["java"] is correct here precisely because every dependency of this module
// is compileOnly: the generated POM carries no compile-scope dependency at all, so a
// consumer never inherits a Paper coordinate from us.
//
// Credentials: nothing lives in Gradle files. They are read from the environment first
// (CI supplies the workflow's automatic GITHUB_TOKEN plus GITHUB_ACTOR, so no secret has
// to be configured anywhere) and fall back to the `gpr.user` / `gpr.token` Gradle
// properties for a developer machine, which belong in ~/.gradle/gradle.properties and
// never in this repository. This repository is PUBLIC, so unlike smp-core there is no
// .env-file lookup here: a dotfile holding a PAT next to public sources is one `git add
// -A` away from being leaked.
// ---------------------------------------------------------------------------
val githubUser = providers.environmentVariable("GITHUB_ACTOR")
    .orElse(providers.environmentVariable("GITHUB_USER"))
    .orElse(providers.gradleProperty("gpr.user"))
    .orElse("")

val githubToken = providers.environmentVariable("GITHUB_TOKEN")
    .orElse(providers.gradleProperty("gpr.token"))
    .orElse("")

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            artifactId = "integrated-models-api"
            pom {
                name = "integrated-models-api"
                description = "Component-based model API for Paper/Folia plugins, backed by the " +
                        "BetterModel rendering library."
                url = "https://github.com/AlepandoCR/integrated-models"
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/AlepandoCR/integrated-models")
            credentials {
                username = githubUser.get()
                password = githubToken.get()
            }
        }
    }
}
