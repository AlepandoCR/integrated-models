package pandastic.studio.models.internal.nms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lookup is the only part of the NMS layer that can be tested without a server, and it is also
 * the part whose failures are silent: an adapter that is never selected produces no error, so the
 * cases worth writing down are the ones where something exists and is passed over anyway.
 */
class NmsBridgeLookupTest {

    private static final String SERVER_VERSION = "26.3";

    /** A provider that announces a version and hands back a distinguishable bridge. */
    private record FixedProvider(String version, NmsBridge bridge) implements NmsBridgeProvider {

        @Override
        public String supportedMinecraftVersion() {
            return this.version;
        }

        @Override
        public NmsBridge create() {
            return this.bridge;
        }
    }

    /** A provider that matches the version and then blows up, as a wrongly-mapped one would. */
    private record ExplodingProvider(String version) implements NmsBridgeProvider {

        @Override
        public String supportedMinecraftVersion() {
            return this.version;
        }

        @Override
        public NmsBridge create() {
            throw new NoSuchMethodError("getGoalSelector()");
        }
    }

    private static NmsBridge bridgeFor(String version) {
        return new NmsBridge() {
            @Override
            public String minecraftVersion() {
                return version;
            }

            @Override
            public BrainBridge brains() {
                return new StubBrainBridge();
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
        };
    }

    @Test
    void selectsTheProviderWhoseVersionMatchesExactly() {
        final NmsBridge expected = bridgeFor(SERVER_VERSION);
        final NmsBridgeResolution resolution = NmsBridgeLookup.resolve(
                SERVER_VERSION,
                List.of(new FixedProvider(SERVER_VERSION, expected)));

        assertAll(
                () -> assertTrue(resolution.isPresent()),
                () -> assertSame(expected, resolution.bridge()),
                () -> assertSame(expected, resolution.require()),
                () -> assertEquals(List.of(), resolution.rejections()));
    }

    /**
     * The whole reason the match is exact. A near-miss has to be reported as a near-miss, because
     * "26.4 on a 26.3 adapter" is the case that would otherwise surface as a NoSuchMethodError from
     * inside a tick.
     */
    @Test
    void rejectsAnAdapterForANeighbouringVersionAndSaysWhich() {
        final NmsBridgeResolution resolution = NmsBridgeLookup.resolve(
                "26.4",
                List.of(new FixedProvider(SERVER_VERSION, bridgeFor(SERVER_VERSION))));

        assertAll(
                () -> assertFalse(resolution.isPresent()),
                () -> assertTrue(resolution.asOptional().isEmpty()),
                () -> assertEquals(1, resolution.rejections().size()),
                () -> assertTrue(
                        resolution.rejections().getFirst().contains("is for Minecraft 26.3"),
                        resolution.rejections()::getFirst),
                () -> assertTrue(resolution.describe().contains("26.4"), resolution::describe));
    }

    /**
     * No provider at all is the state a build with {@code -PintegratedModels.nms=false} ships, and
     * it must not read as a defect — the message has to name the likely cause rather than leave an
     * operator to guess.
     */
    @Test
    void anEmptyClasspathIsExplainedRatherThanTreatedAsAnError() {
        final NmsBridgeResolution resolution = NmsBridgeLookup.resolve(SERVER_VERSION, List.of());

        assertAll(
                () -> assertFalse(resolution.isPresent()),
                () -> assertEquals(List.of(), resolution.rejections()),
                () -> assertTrue(
                        resolution.describe().contains("integratedModels.nms"),
                        resolution::describe),
                () -> assertTrue(
                        resolution.describe().contains("rendering is unaffected"),
                        resolution::describe));
    }

    @Test
    void requireFailsWithTheExplanationRatherThanANullPointer() {
        final NmsBridgeResolution resolution = NmsBridgeLookup.resolve(SERVER_VERSION, List.of());
        final IllegalStateException failure =
                assertThrows(IllegalStateException.class, resolution::require);

        assertEquals(resolution.describe(), failure.getMessage());
    }

    /** Iteration order is preference order, and the runner-up has to be visible in the log. */
    @Test
    void theFirstMatchWinsAndTheRestAreRecorded() {
        final NmsBridge first = bridgeFor(SERVER_VERSION);
        final NmsBridge second = bridgeFor(SERVER_VERSION);
        final NmsBridgeResolution resolution = NmsBridgeLookup.resolve(
                SERVER_VERSION,
                List.of(
                        new FixedProvider(SERVER_VERSION, first),
                        new FixedProvider(SERVER_VERSION, second)));

        assertAll(
                () -> assertSame(first, resolution.bridge()),
                () -> assertEquals(1, resolution.rejections().size()),
                () -> assertTrue(
                        resolution.rejections().getFirst().contains("already matched"),
                        resolution.rejections()::getFirst));
    }

    /**
     * An adapter whose mapped members are absent fails inside {@code create()} with a LinkageError,
     * not an exception. Letting that escape would abort plugin enable and take the rendering
     * pipeline — which needs no NMS at all — down with the AI layer.
     */
    @Test
    void aLinkageErrorFromAProviderIsRecordedRatherThanPropagated() {
        final NmsBridgeResolution resolution = NmsBridgeLookup.resolve(
                SERVER_VERSION,
                List.of(new ExplodingProvider(SERVER_VERSION)));

        assertAll(
                () -> assertFalse(resolution.isPresent()),
                () -> assertEquals(1, resolution.rejections().size()),
                () -> assertTrue(
                        resolution.rejections().getFirst().contains("failed to build its adapter"),
                        resolution.rejections()::getFirst),
                () -> assertTrue(
                        resolution.rejections().getFirst().contains("getGoalSelector"),
                        resolution.rejections()::getFirst));
    }

    /** A broken provider must not consume the one that would have worked. */
    @Test
    void aBrokenProviderDoesNotHideALaterWorkingOne() {
        final NmsBridge working = bridgeFor(SERVER_VERSION);
        final NmsBridgeResolution resolution = NmsBridgeLookup.resolve(
                SERVER_VERSION,
                List.of(
                        new ExplodingProvider(SERVER_VERSION),
                        new FixedProvider(SERVER_VERSION, working)));

        assertAll(
                () -> assertSame(working, resolution.bridge()),
                () -> assertEquals(1, resolution.rejections().size()));
    }

    /**
     * The {@code META-INF/services} path, exercised through the test classpath's own service entry
     * (src/test/resources). This is the only test that proves discovery itself works rather than
     * just the matching rule, and it is worth the fixture: a missing or misspelled service file
     * fails exactly like a module that was never built.
     */
    @Test
    void discoversProvidersDeclaredInMetaInfServices() {
        final NmsBridgeResolution matched =
                NmsBridgeLookup.resolve(ServiceLoadedTestProvider.VERSION);

        assertAll(
                () -> assertTrue(matched.isPresent(), matched::describe),
                () -> assertEquals(
                        ServiceLoadedTestProvider.VERSION,
                        matched.require().minecraftVersion()));
    }

    /** The same discovery, with a version nothing on the classpath claims. */
    @Test
    void discoveryFindsNothingForAnUnknownVersion() {
        final NmsBridgeResolution missed = NmsBridgeLookup.resolve("0.0-not-a-version");

        assertAll(
                () -> assertFalse(missed.isPresent()),
                () -> assertFalse(missed.rejections().isEmpty()),
                () -> assertTrue(
                        missed.describe().contains(ServiceLoadedTestProvider.VERSION),
                        missed::describe));
    }
}
