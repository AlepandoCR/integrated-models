package pandastic.studio.models.internal.nms;

/**
 * A provider reached through {@code META-INF/services} on the test classpath, so that
 * {@link NmsBridgeLookup#resolve(String)} — the overload that actually runs on a server — is covered
 * and not only the injectable one.
 *
 * <h2>Why the version is obviously not a real one</h2>
 * {@link #VERSION} could never be reported by a server. If it were a plausible string like
 * {@code "26.3"} then a test asserting "an adapter was found for 26.3" would pass for the wrong
 * reason the day a real adapter lands on this classpath, and that is the kind of green test that
 * hides a regression instead of catching one.
 *
 * <p>Public with a public no-argument constructor because {@code ServiceLoader} requires both.
 */
public final class ServiceLoadedTestProvider implements NmsBridgeProvider {

    /** A version string no server can report. */
    public static final String VERSION = "0.0-service-loader-fixture";

    /** Required by {@code ServiceLoader}. */
    public ServiceLoadedTestProvider() {
        // Intentionally empty.
    }

    @Override
    public String supportedMinecraftVersion() {
        return VERSION;
    }

    @Override
    public NmsBridge create() {
        return new NmsBridge() {
            @Override
            public String minecraftVersion() {
                return VERSION;
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
}
