// ---------------------------------------------------------------------------
// The engine: everything this plugin does that is not a renderer's job.
//
// NO SHADOW PLUGIN AND NO BETTERMODEL, AND BOTH ABSENCES ARE DELIBERATE
//
// The shippable jar is assembled by integrated-models-bettermodel, which depends on this module. A shadow
// jar contains its own project's classes plus its runtime classpath, so a jar built here would have no
// rendering backend in it — and a jar with no backend is a plugin that refuses to enable, by design. See
// that module's build file for the whole argument and for the NMS bundling that moved with it.
//
// `bettermodel-bukkit-api` is likewise declared in that module ONLY. It was here before the renderer was
// split out; leaving it would let a kr.toxicity import reappear in this module and compile, which is the
// one regression the split exists to make impossible. Nothing under
// pandastic.studio.models.internal may name a renderer type — the seam is
// pandastic.studio.models.internal.spi.ModelRenderBackend, and the backend is found with
// java.util.ServiceLoader.
// ---------------------------------------------------------------------------

dependencies {
    // implementation, not compileOnly: the api interfaces have to travel inside the shaded plugin jar,
    // because the server has no other copy of them. The backend module declares this project as
    // `implementation` in turn, which is what carries both into the jar it assembles.
    implementation(project(":integrated-models-api"))

    // pandastic-core is a shaded, unrelocated library rather than a server plugin: nothing
    // provides it at runtime, so it travels inside our own shaded jar. Every tunable value in
    // this project is declared through its configuration system, and its dialog API is the only
    // one any menu here may use.
    implementation(libs.pandastic.core)

    // The same artifact carries the processors that turn each @Configurable declaration into a
    // GeneratedConfig implementation. It is unrelocated and registers them under
    // META-INF/services, so the coordinate works unchanged on the annotationProcessor path.
    annotationProcessor(libs.pandastic.core)

    // Paper is compileOnly for main because the server provides it, and compileOnly does not reach the
    // test classpath. Tests that load a rig through Bukkit's YamlConfiguration need it on their own
    // compile and runtime classpath. Scoped to the test source set only; main still gets it from the
    // root. No renderer counterpart any more: no test here can touch a BetterModel type, because no
    // source here can.
    testImplementation(rootProject.libs.paper.api)
}

// ---------------------------------------------------------------------------
// The seven shipped rigs, handed to the test JVM as a path.
//
// rigs/ lives at the repository ROOT and not in this module's resources, deliberately: it is a
// server's data folder content, which ModelRuntime reads from disk at enable, not a payload the jar
// carries. ShippedRigsTest loads all seven through the real loader and demands zero diagnostics,
// which is the only test in the project that reads a file an operator is actually handed.
//
// A Test task's working directory is the module directory, so the root cannot be reached by a
// relative path that stays correct if the module ever moves. Passing it in is explicit and fails
// loudly when absent — the suite asserts the property rather than skipping, because a rig suite that
// silently skips is exactly the hole it was written to close.
// ---------------------------------------------------------------------------
tasks.named<Test>("test") {
    systemProperty(
        "integratedModels.rigsDir",
        rootProject.layout.projectDirectory.dir("rigs").asFile.absolutePath
    )
}
