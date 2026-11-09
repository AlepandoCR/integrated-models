package pandastic.studio.models.internal.ai;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.ai.AiController;
import pandastic.studio.models.internal.nms.BrainBridge;
import pandastic.studio.models.internal.nms.MovementBridge;
import pandastic.studio.models.internal.nms.NavigationBridge;
import pandastic.studio.models.internal.nms.NmsBridge;
import pandastic.studio.models.internal.nms.NmsBridgeResolution;
import pandastic.studio.models.internal.nms.RiderInputBridge;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly, and the one state the plugin has to be written against: no adapter.
 *
 * <h2>Why absence is tested first</h2>
 * The rendering pipeline does not need NMS, so a server running a version no shipped adapter was built
 * for must keep rendering and lose only the AI layer. A runtime that threw there would take down working
 * functionality to punish a missing optional one, and the operator would get a startup failure instead
 * of a sentence saying which versions the adapters were for.
 */
class AiRuntimeTest {

    private final FakeAiHost host = new FakeAiHost();
    private final FakeBrainBridge brains = new FakeBrainBridge();
    private final AiSettingsSource settings =
            AiSettingsSource.fixed(new AiSettings(AiFixtures.BOUNDS, 0L, 3));

    /** A plugin reference that throws on every call; nothing in the layer uses it but the real host. */
    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                AiRuntimeTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> 1;
                    case "toString" -> "FakePlugin";
                    default -> throw new AssertionError("The AI layer must not call "
                            + method.getName() + " on the plugin: the only thing that needs it is the"
                            + " real host, which schedules tasks under it");
                });
    }

    private AiRuntime runtime(NmsBridgeResolution resolution) {
        return AiRuntime.create(plugin(), resolution, this.settings,
                (message, failure) -> {
                }, mob -> this.host);
    }

    private static NmsBridgeResolution withBridge(BrainBridge brains) {
        return new NmsBridgeResolution(new NmsBridge() {
            @Override
            public String minecraftVersion() {
                return "26.3";
            }

            @Override
            public BrainBridge brains() {
                return brains;
            }

            @Override
            public NavigationBridge navigation() {
                return new NavigationBridge() {
                };
            }

            @Override
            public RiderInputBridge riderInput() {
                return new RiderInputBridge() {
                };
            }

            @Override
            public MovementBridge movement() {
                return new MovementBridge() {
                };
            }
        }, "26.3", List.of());
    }

    @Test
    void anAbsentAdapterLeavesTheLayerOffWithAnExplanationRatherThanFailing() {
        final AiRuntime runtime = runtime(new NmsBridgeResolution(null, "26.9",
                List.of("some.Adapter is for Minecraft 26.3")));

        assertAll(
                () -> assertTrue(runtime.creatureAi().isEmpty()),
                () -> assertTrue(runtime.unavailableReason().contains("26.9"),
                        runtime::unavailableReason),
                () -> assertTrue(runtime.unavailableReason().contains("Model rendering is unaffected"),
                        () -> "the operator has to be told what still works: "
                                + runtime.unavailableReason()),
                () -> assertEquals(0, runtime.shutdown(), "shutting down with no layer is a no-op")
        );
    }

    @Test
    void anAdapterProducesAUsableService() {
        final AiRuntime runtime = runtime(withBridge(this.brains));

        assertAll(
                () -> assertTrue(runtime.creatureAi().isPresent()),
                () -> assertEquals("", runtime.unavailableReason()),
                () -> assertEquals(AiFixtures.BOUNDS, runtime.creatureAi().orElseThrow().getBounds())
        );
    }

    @Test
    void shutdownUninstallsEveryLivePlanAndCancelsItsTasks() {
        // The ordering that is not optional: a repeating task dispatched through a creature's scheduler
        // is never cancelled for a plugin on disable, so one left behind fails on the first lazy class
        // load after the classloader closes.
        final AiRuntime runtime = runtime(withBridge(this.brains));
        final CompletableFuture<AiController> installing = runtime.creatureAi().orElseThrow()
                .install(AiFixtures.minimal("guard")
                        .sensor(AiFixtures.sensor("look", Set.of()), 4L)
                        .build(), this.host.mob());
        this.host.drainSubmitted();
        final AiController controller = installing.join();

        assertAll(
                () -> assertEquals(1L, this.host.liveTaskCount()),
                () -> assertEquals(1, runtime.shutdown()),
                () -> assertFalse(controller.isInstalled()),
                () -> assertEquals(0L, this.host.liveTaskCount()),
                () -> assertEquals(0, runtime.shutdown(), "a second shutdown finds nothing left")
        );
    }
}
