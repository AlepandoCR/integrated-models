import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

// ---------------------------------------------------------------------------
// The BetterModel rendering backend, and the module that assembles the shippable jar.
//
// WHY A MODULE
//
// Exactly one thing this project does is a renderer's job: turning a finished pose into packets a client
// understands. Everything else — rigs, physics, the gait, emission cadence, culling, menus, the AI layer —
// is arithmetic and ordering over plain values. BetterModel is the renderer today and will not be the
// only one, so the renderer-specific half lives here and reaches the engine only through
// pandastic.studio.models.internal.spi.ModelRenderBackend. Another renderer is another module beside this
// one plus its own META-INF/services entry; integrated-models-internal does not change.
//
// THE DEPENDENCY DIRECTION, WHICH IS THE WHOLE POINT
//
// This module depends on integrated-models-internal. The engine declares nothing about any backend on any
// classpath and holds no string naming one: discovery is java.util.ServiceLoader. Three things follow, and
// each of them is the reason the direction is this way round rather than the other:
//
//   * the engine's compileJava, test compile and test runtime never see BetterModel, so every one of its
//     test suites runs with no renderer anywhere — which is what makes the rig, pose, physics,
//     locomotion, render and sim layers testable at all;
//   * the seam interfaces stay compilable without the renderer installed, which is what makes
//     "no backend present" a runtime state the plugin is written against rather than a build failure; and
//   * nothing in the engine can acquire a kr.toxicity reference by accident, because the coordinate is
//     not on its classpath to acquire.
//
// WHY THE SHADED JAR IS BUILT HERE
//
// Because the deliverable has to contain the backend, and a shadow jar contains its own project's classes
// plus its runtime classpath. Built from the engine, as it was before this module existed, the jar would
// have no backend in it and the plugin would refuse to enable — correctly, and uselessly. So the shadow
// plugin and the jar assembly moved here, the root project's copyJar reads this module's shadowJar, and
// the JavaPlugin main class stays in the engine: a jar may hold classes from several modules, and
// plugin.yml travels in with the engine's own resources.
// ---------------------------------------------------------------------------

dependencies {
    // implementation, not compileOnly: the engine's classes and the api interfaces have to travel inside
    // the shaded jar, because the server has no other copy of them. This is also what puts the engine's
    // own implementation dependencies — pandastic-core among them — on this module's runtime classpath,
    // and therefore into the jar.
    implementation(project(":integrated-models-internal"))

    // Declared even though the engine already depends on it, because the engine declares it as
    // `implementation`, which deliberately does not expose it to consumers. This module's own signatures
    // name api types all over — a backend binds a Rig and returns a PoseSink — so it needs them on its
    // compile classpath in its own right.
    implementation(project(":integrated-models-api"))

    // BetterModel is a separate server plugin (plugin.yml declares `depend: [BetterModel]`), so its
    // classes are provided at runtime and must never be shaded in.
    //
    // THIS IS THE ONLY MODULE THAT MAY DECLARE IT. It used to live in integrated-models-internal and was
    // removed from there as part of the split; putting it back would let a kr.toxicity import reappear in
    // the engine and compile, which is exactly the regression this module exists to make impossible.
    compileOnly(libs.bettermodel.bukkit.api)

    // compileOnly does not reach the test classpath, and paper-api arrives from the root as compileOnly
    // for main only. A test here that touches a Bukkit or a BetterModel type needs both on its own
    // compile classpath. Scoped to the test source set; main still gets paper-api from the root.
    testImplementation(rootProject.libs.paper.api)
    testCompileOnly(libs.bettermodel.bukkit.api)
}

// ---------------------------------------------------------------------------
// Paper decides whether to remap a plugin jar from Spigot names to Mojang names at load time by reading
// this manifest attribute, and a jar that does not carry it is treated as legacy Spigot-mapped and
// remapped. The NMS layer is compiled Mojang-mapped, so without this the server would rewrite correct
// references into wrong ones at load — the failure arrives as a NoSuchMethodError in a tick and points at
// our class rather than at the manifest.
//
// paperweight writes this attribute itself, but only onto the jars of the project it is applied to, and it
// is applied to integrated-models-nms rather than to this module. So the shaded jar has to declare it
// here. Unconditional, including when the NMS module is excluded: no code in this repository has ever been
// Spigot-mapped, so the claim is true either way and one jar shape is easier to reason about than two.
//
// mergeServiceFiles() is not optional any more. This jar now carries at least two META-INF/services
// entries — this module's ModelRenderBackendProvider and, when the NMS module is in the build, its
// NmsBridgeProvider — and shadow's default is to let one file win. A lost service file is a seam that
// silently finds no provider, which for the backend means a plugin that refuses to enable.
//
// The per-file duplicatesStrategy is the other half of making that merge actually happen, and it is the
// fix for four build warnings shadow emits without it:
//
//   'META-INF/services/…' is matched by …ServiceFileTransformer but its DuplicatesStrategy is EXCLUDE
//   — duplicates may be silently dropped before the transformer processes them.
//
// Shadow's task-level default is EXCLUDE, which drops the second and later copies of a path *before* a
// transformer is consulted. mergeServiceFiles() then merges one file with nothing and reports success.
// Nothing is lost in today's jar — the four service paths each come from exactly one jar — but the
// warning is load-bearing rather than cosmetic: the day a second module ships the same service path,
// EXCLUDE loses it silently and the symptom is a seam with no provider at runtime.
//
// Scoped with filesMatching to META-INF/services/** rather than set on the task, which is what shadow's
// own advice ("set it to INCLUDE or WARN") would do. Task-wide INCLUDE would also duplicate every
// LICENSE, NOTICE and module-info that two dependencies happen to share, turning a merge fix into a jar
// full of repeated entries. Only the paths a transformer claims need to reach it.
// ---------------------------------------------------------------------------
tasks.named<ShadowJar>("shadowJar") {
    manifest {
        attributes("paperweight-mappings-namespace" to "mojang")
    }
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

// ---------------------------------------------------------------------------
// The NMS layer, carried into the shaded jar and nowhere else.
//
// Moved here verbatim from integrated-models-internal when the jar assembly moved: integrated-models-nms
// compiles against the engine's pandastic.studio.models.internal.nms seam and the engine declares nothing
// about it, so the bundling is a task edge rather than a compile edge and belongs to whichever module
// produces the jar.
//
// A dedicated, non-transitive configuration rather than `runtimeOnly`: runtimeOnly is extended by
// testRuntimeOnly, so it would put the nms jar on this module's test runtime classpath and make every test
// run here wait on a server remap. Nothing extends this one.
//
// The project lookup is nullable because settings.gradle.kts can leave the module out; see the comment
// there. When it is out, the shaded jar simply has no NMS layer and the ServiceLoader lookup in the nms
// seam finds no provider — the same path a server on an unsupported Minecraft version takes. The rendering
// backend is unaffected either way, which is the point of them being separate seams.
// ---------------------------------------------------------------------------
val nmsProject = rootProject.findProject(":${rootProject.name}-nms")

if (nmsProject != null) {
    // Two configurations rather than one because Gradle 9 separates the roles: a dependency-scope
    // configuration is the only kind a dependency may be declared against, and a resolvable one is the
    // only kind shadow can read files from.
    val nmsLayerDependencies = configurations.dependencyScope("nmsLayerDependencies") {
        description = "Declares the NMS adapter module that the shaded plugin jar bundles."
    }

    val nmsLayer = configurations.resolvable("nmsLayer") {
        description = "The Mojang-mapped NMS adapter, bundled into the shaded plugin jar."
        extendsFrom(nmsLayerDependencies.get())
        isTransitive = false
    }

    dependencies {
        // Targeted at a named configuration rather than resolved by attribute, because paperweight
        // publishes a second runtime variant (`reobf`, the Spigot-remapped jar) that is ambiguous with
        // `runtimeElements` for any ordinary runtime consumer. See the nms module's build file: picking
        // the wrong one would ship Spigot-mapped classes in a jar whose manifest claims Mojang mappings,
        // and that only fails on a server.
        add(
            nmsLayerDependencies.name,
            project(path = nmsProject.path, configuration = "nmsLayerElements")
        )
    }

    tasks.named<ShadowJar>("shadowJar") {
        configurations.add(project.configurations.named("runtimeClasspath"))
        configurations.add(nmsLayer)
    }
}
