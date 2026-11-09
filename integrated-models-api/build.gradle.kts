plugins {
    `maven-publish`
}

// This module is interfaces only. It must never gain a BetterModel (kr.toxicity.*)
// dependency: anything that imports the rendering library belongs in
// integrated-models-internal. paper-api, the annotation jars and the JUnit harness all
// come from the root subprojects {} block.

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
