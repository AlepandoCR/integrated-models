package pandastic.studio.models.internal.input;

import org.bukkit.entity.Mob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.input.InputCapability;
import pandastic.studio.models.internal.nms.BrainBridge;
import pandastic.studio.models.internal.nms.BrainInstallRequest;
import pandastic.studio.models.internal.nms.BrainInstallation;
import pandastic.studio.models.internal.nms.InputCaptureBridge;
import pandastic.studio.models.internal.nms.MovementBridge;
import pandastic.studio.models.internal.nms.NavigationBridge;
import pandastic.studio.models.internal.nms.NmsBridge;
import pandastic.studio.models.internal.nms.RiderInputBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The degraded paths, which are the only part of the runtime's assembly that can be got wrong quietly.
 *
 * <h2>Three refusals, three sentences</h2>
 * There are three reasons a server has no packet-level capture and the operator response to each is
 * different: the adapter module was left out of the build, no adapter shipped for this Minecraft
 * version or the one that did cannot reach the connection, or the operator switched it off. A single
 * boolean would collapse them into "it is off" and leave an operator with nothing to act on, which is
 * why the decision returns a sentence and why each of them is asserted here by the part that tells them
 * apart rather than by its whole wording.
 *
 * <h2>What must keep working</h2>
 * All of it except suppressing a movement key and seeing an input early. That is the claim the
 * capability assertions make: a degraded server still observes both families and can still stop an
 * action.
 */
@DisplayName("InputRuntime assembly")
final class InputRuntimeTest {

    @Test
    @DisplayName("with no adapter at all, the platform source stands alone and names the build")
    void noAdapter() {
        final List<InputCaptureSource> sources = new ArrayList<>();

        final String reason = InputRuntime.packetSource(
                null,
                InputSettingsSource.fixed(InputFixtures.defaults()),
                new InputFixtures.ManualClock(),
                sources);

        assertEquals(List.of(), sources, "there is nothing to add");
        assertNotNull(reason);
        assertTrue(reason.contains("-PintegratedModels.nms=false"),
                "the sentence has to name the build switch, which is the thing an operator can change");
        assertTrue(reason.contains("cannot be suppressed"));
    }

    @Test
    @DisplayName("with an adapter whose capture bridge declines, the bridge's own reason is quoted")
    void decliningBridge() {
        // The bridge is present, matched to the version, and still unable to reach the connection — a
        // server fork that moved it, or a packet whose shape no longer matches. Only the bridge knows
        // why, so only the bridge can say.
        final List<InputCaptureSource> sources = new ArrayList<>();

        final String reason = InputRuntime.packetSource(
                new FakeNmsBridge(new InputCaptureBridge() {
                    @Override
                    public String describe() {
                        return "QQ-the-packet-shape-moved-QQ";
                    }
                }),
                InputSettingsSource.fixed(InputFixtures.defaults()),
                new InputFixtures.ManualClock(),
                sources);

        assertEquals(List.of(), sources);
        assertNotNull(reason);
        assertTrue(reason.contains("QQ-the-packet-shape-moved-QQ"));
    }

    @Test
    @DisplayName("with the key switched off, the sentence names the key and not the build")
    void switchedOff() {
        final List<InputCaptureSource> sources = new ArrayList<>();

        final String reason = InputRuntime.packetSource(
                new FakeNmsBridge(new AvailableCapture()),
                InputSettingsSource.fixed(InputFixtures.withPacketSourceOff()),
                new InputFixtures.ManualClock(),
                sources);

        assertEquals(List.of(), sources, "an operator switch must not install the source");
        assertNotNull(reason);
        assertTrue(reason.contains("packetSourceEnabled"),
                "an operator who turned it off has to be able to find the key again");
        assertFalse(reason.contains("-PintegratedModels.nms=false"),
                "naming the build switch here would send the operator to rebuild for nothing");
    }

    @Test
    @DisplayName("with an available bridge, the packet source is added and nothing is missing")
    void available() {
        final List<InputCaptureSource> sources = new ArrayList<>();

        final String reason = InputRuntime.packetSource(
                new FakeNmsBridge(new AvailableCapture()),
                InputSettingsSource.fixed(InputFixtures.defaults()),
                new InputFixtures.ManualClock(),
                sources);

        assertNull(reason, "nothing is missing, so there is nothing to log");
        assertEquals(1, sources.size());
        final InputCaptureSource source =
                assertInstanceOf(BridgeInputCaptureSource.class, sources.getFirst());
        assertEquals(
                Set.of(InputCapability.SUPPRESS_MOVEMENT, InputCapability.OBSERVE_BEFORE_SERVER),
                source.capabilities(),
                "the packet source contributes the two capabilities nothing else can");
    }

    @Test
    @DisplayName("the packet source does not claim the observation the platform source already has")
    void capabilitiesDoNotOverlapMisleadingly() {
        // If it claimed OBSERVE_MOVEMENT as its own contribution, the published capability set could no
        // longer distinguish "this server can see movement" from "this server can stop it" — and the
        // whole registration-time check is built on that distinction.
        final InputCaptureSource packets =
                new BridgeInputCaptureSource(new AvailableCapture(), new InputFixtures.ManualClock());

        assertFalse(packets.capabilities().contains(InputCapability.OBSERVE_MOVEMENT));
        assertTrue(packets.capabilities().contains(InputCapability.SUPPRESS_MOVEMENT));
    }

    /** A capture bridge that reports itself installable and does nothing else. */
    private static final class AvailableCapture implements InputCaptureBridge {

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /**
     * An {@link NmsBridge} that carries one capture bridge and declines everything else.
     *
     * <p>The other four seams are reached through their declining defaults, which is the whole reason
     * this fixture is four lines: a bridge that has to implement the brain surface to be constructed
     * would make every test of any other seam depend on the AI layer.
     */
    private record FakeNmsBridge(InputCaptureBridge capture) implements NmsBridge {

        @Override
        public String minecraftVersion() {
            return "test";
        }

        @Override
        public BrainBridge brains() {
            return new BrainBridge() {

                @Override
                public boolean supports(Mob mob) {
                    return false;
                }

                @Override
                public List<String> supportedSpecies() {
                    return List.of();
                }

                @Override
                public BrainInstallation install(BrainInstallRequest request) {
                    // A test that reaches this has strayed out of its subject: the input layer does
                    // not install brains and never will.
                    throw new UnsupportedOperationException("not the input layer's business");
                }
            };
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

        @Override
        public InputCaptureBridge inputCapture() {
            return this.capture;
        }
    }
}
