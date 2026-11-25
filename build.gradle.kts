plugins {
    id("base")
}

allprojects {
    group = "pandastic.studio"
    version = "1.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        // Paper 26.3 is compiled for Java 25; matching it keeps the plugin loadable.
        toolchain.languageVersion = JavaLanguageVersion.of(25)
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Shared compile-time libraries, handed to every module from here. No module has to
    // declare them and no module has to depend on another one to get them.
    // compileOnly keeps them off the runtime classpath, so they never land in the shaded
    // jar nor in the published POM: the server already provides Paper, and the annotation
    // jars are compile-time only.
    //
    // paper-api alone is enough for a Folia target for everything that is not NMS. The only
    // Folia-specific API the api and internal modules touch is
    // io.papermc.paper.threadedregions.scheduler.* (EntityScheduler, ScheduledTask, …), which
    // paper-api already ships, and neither module uses NMS or Mojang mappings — so neither
    // applies paperweight, their builds never download or remap a server jar, and the
    // published api jar stays free of any dependency on one. Mojang-mapped access is confined
    // to integrated-models-nms; see that module's build file.
    dependencies {
        // Deliberately NOT given to the nms module. Its Folia dev bundle brings
        // dev.folia:folia-api, a different coordinate carrying the same org.bukkit.* classes
        // at a different build (26.3.build.6-alpha against paper-api's 237-stable). Gradle
        // cannot see those as one module, so it would not resolve the conflict: both land on
        // the compile classpath and whichever comes first wins, silently and depending on
        // resolution order. One source of Bukkit per module.
        if (name != "integrated-models-nms") {
            "compileOnly"(rootProject.libs.paper.api)
        }

        "compileOnly"(rootProject.libs.annotations)
        "compileOnly"(rootProject.libs.jspecify)

        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        "testCompileOnly"(rootProject.libs.annotations)
    }
}

// The root project holds no sources. It exists to aggregate the modules and to
// produce the single file an operator drops into a server's plugins/ folder.
val apiProject = project(":${rootProject.name}-api")
val internalProject = project(":${rootProject.name}-internal")

// The rendering backend, and the module that assembles the shaded jar. It is the backend rather than the
// engine because a shadow jar contains its own project's classes plus its runtime classpath: built from
// the engine, the jar would carry no backend and the plugin would refuse to enable. Swapping renderer
// means repointing this one line at the new backend module.
val backendProject = project(":${rootProject.name}-bettermodel")

tasks {
    val copyJar = register<Copy>("copyJar") {
        description = "Copies the shaded JAR from the rendering backend module into this project's build/libs directory"
        val backendShadowTask = backendProject.tasks.named("shadowJar")
        dependsOn(backendShadowTask)

        from(backendShadowTask)
        into(layout.buildDirectory.dir("libs"))

        rename { "${rootProject.name}.jar" }
    }

    named("build") {
        dependsOn(
            apiProject.tasks.named("build"),
            internalProject.tasks.named("build"),
            backendProject.tasks.named("build"),
            copyJar
        )

        // The NMS module is excludable from settings.gradle.kts, so it is aggregated only when
        // it is actually in the build. copyJar already pulls its classes in through shadowJar;
        // this adds its own check/assemble so a root `build` reports its failures too.
        rootProject.findProject(":${rootProject.name}-nms")?.let {
            dependsOn(it.tasks.named("build"))
        }
    }
}
