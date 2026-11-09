import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    // implementation, not compileOnly: the api interfaces have to travel inside the
    // shaded plugin jar, because the server has no other copy of them.
    implementation(project(":integrated-models-api"))

    // BetterModel is a separate server plugin (plugin.yml declares `depend: [BetterModel]`),
    // so its classes are provided at runtime and must never be shaded in.
    compileOnly(libs.bettermodel.bukkit.api)

    // pandastic-core is a shaded, unrelocated library rather than a server plugin: nothing
    // provides it at runtime, so it travels inside our own shaded jar. Every tunable value in
    // this project is declared through its configuration system, and its dialog API is the only
    // one any menu here may use.
    implementation(libs.pandastic.core)

    // The same artifact carries the processors that turn each @Configurable declaration into a
    // GeneratedConfig implementation. It is unrelocated and registers them under
    // META-INF/services, so the coordinate works unchanged on the annotationProcessor path.
    annotationProcessor(libs.pandastic.core)

    // Paper and BetterModel are compileOnly for main because the server provides both, and
    // compileOnly does not reach the test classpath. Tests that load a rig through Bukkit's
    // YamlConfiguration, or that touch a BetterModel type, need them on their own compile and
    // runtime classpath. Scoped to the test source set only; main still gets them from the root.
    testImplementation(rootProject.libs.paper.api)
    testCompileOnly(libs.bettermodel.bukkit.api)
}

// ---------------------------------------------------------------------------
// Paper decides whether to remap a plugin jar from Spigot names to Mojang names at load time by
// reading this manifest attribute, and a jar that does not carry it is treated as legacy
// Spigot-mapped and remapped. The NMS layer is compiled Mojang-mapped, so without this the server
// would rewrite correct references into wrong ones at load — the failure arrives as a
// NoSuchMethodError in a tick and points at our class rather than at the manifest.
//
// paperweight writes this attribute itself, but only onto the jars of the project it is applied to,
// and it is applied to integrated-models-nms rather than to this module. So the shaded jar has to
// declare it here. Unconditional, including when the NMS module is excluded: no code in this
// repository has ever been Spigot-mapped, so the claim is true either way and one jar shape is
// easier to reason about than two.
// ---------------------------------------------------------------------------
tasks.named<ShadowJar>("shadowJar") {
    manifest {
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}

// ---------------------------------------------------------------------------
// The NMS layer, carried into the shaded jar and nowhere else.
//
// The dependency runs the other way round from what the shaded jar suggests:
// integrated-models-nms declares `compileOnly(project(":integrated-models-internal"))` to see the
// pandastic.studio.models.internal.nms seam it implements, and this module declares nothing about
// it on any classpath. That direction is the whole point —
//
//   * this module's compileJava, test compile and test runtime never see paperweight, so
//     `:integrated-models-internal:build` and every test here run with no dev bundle anywhere; and
//   * the seam interfaces stay compilable without a Mojang-mapped server, which is what makes
//     "no implementation present" a state the runtime can be written against rather than a build
//     failure.
//
// A dedicated, non-transitive configuration rather than `runtimeOnly`: runtimeOnly is extended by
// testRuntimeOnly, so it would put the nms jar on this module's test runtime classpath and make
// every test run here wait on a server remap. Nothing extends this one.
//
// The project lookup is nullable because settings.gradle.kts can leave the module out; see the
// comment there. When it is out, the shaded jar simply has no NMS layer and the ServiceLoader
// lookup in the nms seam finds no provider — the same path a server on an unsupported Minecraft
// version takes.
// ---------------------------------------------------------------------------
val nmsProject = rootProject.findProject(":${rootProject.name}-nms")

if (nmsProject != null) {
    // Two configurations rather than one because Gradle 9 separates the roles: a dependency-scope
    // configuration is the only kind a dependency may be declared against, and a resolvable one is
    // the only kind shadow can read files from.
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
        // publishes a second runtime variant (`reobf`, the Spigot-remapped jar) that is ambiguous
        // with `runtimeElements` for any ordinary runtime consumer. See the nms module's build file:
        // picking the wrong one would ship Spigot-mapped classes in a jar whose manifest claims
        // Mojang mappings, and that only fails on a server.
        add(
            nmsLayerDependencies.name,
            project(path = nmsProject.path, configuration = "nmsLayerElements")
        )
    }

    tasks.named<ShadowJar>("shadowJar") {
        // Both entries are named explicitly, and the runtime classpath is NOT redundant. Shadow's
        // convention for this property is [runtimeClasspath], but a Gradle collection property
        // discards its convention the moment a value is added — so `add(nmsLayer)` alone leaves the
        // property holding nothing but nmsLayer, and the api module and pandastic-core silently fall
        // out of the shaded jar. That drops it from 328 classes to 120 and produces a plugin that
        // loads and then dies on the first configuration read.
        configurations.add(project.configurations.named("runtimeClasspath"))
        configurations.add(nmsLayer)
    }
}
