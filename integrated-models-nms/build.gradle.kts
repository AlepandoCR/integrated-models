plugins {
    alias(libs.plugins.paperweight.userdev)
}

// ---------------------------------------------------------------------------
// The Mojang-mapped NMS layer, and the only place in this repository where paperweight runs.
//
// WHY A MODULE, AND NOT A SOURCE SET OR THE INTERNAL MODULE
//
// paperweight-userdev is a project-scoped plugin with project-scoped side effects, and all three of
// them are unacceptable anywhere but here:
//
//  1. It adds the Mojang-mapped server to the project's `compileOnly` and `testImplementation`
//     configurations (PaperweightUserExtension.addServerDependencyTo defaults to exactly those two).
//     There is no supported way to scope that to one source set. Applied to the internal module, an
//     alpha-channel Folia server jar would be on the compile classpath of the rig, physics,
//     locomotion, render and sim layers — which are deliberately platform-free so they can run off
//     the tick — and on the runtime classpath of every test suite in the project.
//  2. It resolves the dev bundle while Gradle CONFIGURES the project, not while a task runs. Every
//     invocation on a build containing this module needs that ~26 MB zip cached or reachable. Keeping
//     it in its own module is what lets settings.gradle.kts take the whole thing out of the build.
//  3. It registers project-level repositories (mache, the plugin remapper, and the dev bundle's own
//     library repositories). Under Gradle's default PREFER_PROJECT repositories mode, a project that
//     declares any repository ignores the ones from settings.gradle.kts entirely — so applying
//     paperweight silently changes where every other dependency of that project resolves from. That
//     is survivable in a module with four dependencies and would be a trap in the internal module.
//
// The dependency direction is the other half of the isolation: this module compiles against the
// internal module, and the internal module declares nothing about this one on any classpath. It
// bundles the output into the shaded jar through a dedicated configuration, which is a task edge and
// not a compile edge. So `:integrated-models-api:build`, `:integrated-models-internal:build` and
// every test still work with no dev bundle anywhere, and the seam interfaces stay compilable without
// a Mojang-mapped server — which is what makes "no adapter present" a runtime state the plugin is
// written against instead of a build failure.
//
// WHAT A CONTRIBUTOR WITHOUT THE DEV BUNDLE SEES
//
// `Could not resolve dev.folia:dev-bundle:26.3.build.6-alpha … Required by: project
// :integrated-models-nms`. The way out is `-PintegratedModels.nms=false`, which drops this module
// from the build; see settings.gradle.kts. The api and internal modules build and test unchanged,
// and the shaded jar is produced without the NMS layer.
// ---------------------------------------------------------------------------

// Declared explicitly because paperweight registers repositories on this project, which under
// PREFER_PROJECT disables the ones from settings.gradle.kts for it. Both of these would in fact
// arrive as a side effect of the dev bundle's own `libraryRepositories`, but relying on what Folia
// happens to list there means annotations, jspecify and JUnit resolve by luck. mavenLocal and the
// pandastic-core repository are left out on purpose: nothing here depends on pandastic-core, because
// nothing below the NMS seam reads configuration.
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    // The Folia dev bundle. Pinned exactly in the version catalog, where the reason lives: 26.3 has
    // never been promoted past Folia's alpha channel, so a range here would change the compile
    // target with no change to this repository.
    paperweight.devBundle(libs.folia.dev.bundle)

    // compileOnly, and a project dependency rather than a module one: this is where the
    // pandastic.studio.models.internal.nms seam lives. compileOnly because the internal module's
    // classes are already in the shaded jar — adding them again would duplicate every class in it.
    compileOnly(project(":integrated-models-internal"))

    // The seam's own signatures name api types — a BrainBridge installs an AiPlan — and the internal
    // module declares the api module as `implementation`, which deliberately does not expose it to
    // consumers. So it has to be declared here too, and for the same reason: the api classes travel
    // inside the shaded jar already, so a runtime-scoped dependency would duplicate them.
    compileOnly(project(":integrated-models-api"))
}

// ---------------------------------------------------------------------------
// Turn a missing dev bundle into a message that names the way out.
//
// paperweight resolves the bundle in its own `afterEvaluate`, and all a contributor sees there is
// `Could not find dev.folia:dev-bundle:…` against a configuration they have never heard of. That is
// accurate and still a mystery: it does not say that only the AI layer needs it, and it does not say
// how to build without it. Resolving it here — eagerly, in the script body, which runs before any
// afterEvaluate — gets the first word in. There is no extra cost: paperweight resolves the same
// configuration moments later, and the second resolution is served from the same cache.
// ---------------------------------------------------------------------------
runCatching { configurations.getByName("paperweightDevelopmentBundle").resolve() }
    .onFailure { cause ->
        throw GradleException(
            """
            Could not resolve the Folia dev bundle ${libs.folia.dev.bundle.get()}.

            Only this module needs it: it is the Mojang-mapped NMS layer, and the dev bundle is a
            ~26 MB zip from https://repo.papermc.io. Nothing else in the project depends on it.

            To build and test everything except the NMS layer, exclude this module:

                ./gradlew build -PintegratedModels.nms=false

            The api and internal modules, and every test suite, build unchanged that way. The shaded
            plugin jar is still produced, without the NMS layer in it.

            If you expected it to resolve, check network access to repo.papermc.io and that the
            `folia` version in gradle/libs.versions.toml still exists on that repository — 26.3 is
            published on Folia's alpha channel, where old builds are not guaranteed to stay.
            """.trimIndent(),
            cause
        )
    }

// ---------------------------------------------------------------------------
// The version this adapter declares itself for is GENERATED from the dev bundle.
//
// NmsBridgeLookup matches a provider's declared Minecraft version against the running server's
// exactly, so that string has to be the version this module was actually compiled against. Written
// as a literal in Java it is a second source of truth: bumping the `folia` version in the catalog
// would compile the adapter against the new NMS and leave it announcing the old version, and the
// result is an adapter that is silently never selected — no error, no log line, just no AI.
// paperweight already knows the answer (it reads it out of the dev bundle's config.json), so taking
// it from there makes the two unable to disagree.
// ---------------------------------------------------------------------------
val generatedSourceDir = layout.buildDirectory.dir("generated/sources/nmsTarget/java")

val generateNmsTarget = tasks.register("generateNmsTarget") {
    description = "Generates NmsTarget, carrying the Minecraft version of the configured dev bundle."

    val minecraftVersion = paperweight.minecraftVersion
    val outputDir = generatedSourceDir

    inputs.property("minecraftVersion", minecraftVersion)
    outputs.dir(outputDir)

    doLast {
        val target = outputDir.get().asFile.resolve("pandastic/studio/models/nms/NmsTarget.java")
        target.parentFile.mkdirs()
        target.writeText(
            """
            package pandastic.studio.models.nms;

            /**
             * The Minecraft version of the Folia dev bundle this module was compiled against.
             *
             * <p>GENERATED at build time from paperweight's view of the dev bundle — do not edit,
             * and do not replace with a literal. See integrated-models-nms/build.gradle.kts for why
             * a literal here would make the adapter silently unselectable after a version bump.
             */
            public final class NmsTarget {

                /** The version string, in the form the server reports its own. */
                public static final String MINECRAFT_VERSION = "${minecraftVersion.get()}";

                private NmsTarget() {
                    throw new AssertionError("No instances.");
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.named("main") {
    java.srcDir(generateNmsTarget)
}

// ---------------------------------------------------------------------------
// How the shaded jar picks this module's output up.
//
// A plain `project(":integrated-models-nms")` dependency does not resolve: paperweight registers a
// second consumable runtime variant, `reobf`, carrying the Spigot-remapped jar, and it matches every
// attribute an ordinary runtime consumer asks for. Gradle reports the ambiguity rather than guessing,
// which is the right behaviour — picking `reobf` would put Spigot-mapped classes in a jar whose
// manifest declares Mojang mappings, and that failure would only appear on a server.
//
// The documented disambiguator is paperweight's own Obfuscation attribute, but requesting it means
// its interface has to be on the *consumer's* buildscript classpath — i.e. the internal module would
// have to carry paperweight just to name an attribute. A purpose-built consumable configuration,
// consumed by name, says exactly what is wanted instead, and stays correct no matter which variants
// paperweight adds next.
// ---------------------------------------------------------------------------
val nmsLayerElements = configurations.consumable("nmsLayerElements") {
    description = "This module's jar, for integrated-models-internal to bundle into the shaded jar."
}

artifacts {
    add(nmsLayerElements.name, tasks.named<Jar>("jar"))
}

paperweight {
    // Narrowed from the default (compileOnly + testImplementation). testImplementation would put the
    // Mojang-mapped server on this module's test RUNTIME classpath, which is misleading: NMS classes
    // cannot be exercised in a unit test — they need a bootstrapped registry — so a test that links
    // against them would fail on first touch and look like our bug. testCompileOnly keeps a future
    // signature-level test possible and leaves the runtime honest.
    addServerDependencyTo.set(
        setOf(
            configurations.getByName("compileOnly"),
            configurations.getByName("testCompileOnly"),
        )
    )

    // reobfArtifactConfiguration is left at its default, MOJANG_PRODUCTION: Paper and Folia have run
    // Mojang-mapped in production since 1.20.5, so `jar` is the shippable artifact and there is
    // nothing to reobfuscate. The `reobfJar` task paperweight registers anyway would fail if it ever
    // ran — a mache-era dev bundle carries no reobf mappings — and nothing depends on it.
}
