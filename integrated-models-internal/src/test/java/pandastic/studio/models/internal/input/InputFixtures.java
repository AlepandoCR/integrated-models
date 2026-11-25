package pandastic.studio.models.internal.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.input.InputTuning;
import pandastic.studio.models.internal.config.ConfigSnapshot;
import pandastic.studio.models.internal.sim.SimulationClock;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The players, clocks and settings the tests in this package drive the input layer with.
 *
 * <h2>Why the players are proxies</h2>
 * Because the input layer only ever asks a {@link Player} two things — its identity and its name — and
 * both are answerable without a server. Everything else it does with a player it hands to a seam: the
 * region hop goes through {@link RegionDispatcher} and the connection goes through the NMS bridge. A
 * proxy that answers those two and throws for the rest is therefore not a weak fake, it is a proof that
 * nothing in the layer reads a player it is not allowed to.
 *
 * <h2>Why the default settings come from the declared defaults</h2>
 * So that a test asserting "a held binding repeats at the configured interval" is asserting against the
 * number an operator would actually get, and a default that drifted away from what the validator
 * accepts would fail here rather than on a server.
 */
final class InputFixtures {

    private InputFixtures() {
    }

    /** The declared defaults of {@code input.yml}, as the input layer sees them. */
    static @NotNull InputSettings defaults() {
        return ConfigSnapshot.defaults().input();
    }

    /**
     * Settings with explicit windows, for a test that is about one of them.
     *
     * @param tuning the four durations
     * @return settings carrying {@code tuning} and the declared defaults for everything else
     */
    static @NotNull InputSettings withTuning(@NotNull InputTuning tuning) {
        final InputSettings base = defaults();
        return new InputSettings(
                tuning,
                base.packetSourceEnabled(),
                base.actionCaptureEnabled(),
                base.pulseVisibilityNanos(),
                base.maxBindings(),
                base.faultLogIntervalNanos(),
                base.faultsBeforeDisable());
    }

    /**
     * Settings with an explicit binding ceiling.
     *
     * @param maxBindings the ceiling
     * @return settings carrying it and the declared defaults for everything else
     */
    static @NotNull InputSettings withMaxBindings(int maxBindings) {
        final InputSettings base = defaults();
        return new InputSettings(
                base.tuning(),
                base.packetSourceEnabled(),
                base.actionCaptureEnabled(),
                base.pulseVisibilityNanos(),
                maxBindings,
                base.faultLogIntervalNanos(),
                base.faultsBeforeDisable());
    }

    /**
     * Settings with an explicit fault policy.
     *
     * @param faultsBeforeDisable consecutive failures after which a binding is closed
     * @return settings carrying it and the declared defaults for everything else
     */
    static @NotNull InputSettings withFaultLimit(int faultsBeforeDisable) {
        final InputSettings base = defaults();
        return new InputSettings(
                base.tuning(),
                base.packetSourceEnabled(),
                base.actionCaptureEnabled(),
                base.pulseVisibilityNanos(),
                base.maxBindings(),
                base.faultLogIntervalNanos(),
                faultsBeforeDisable);
    }

    /** Settings with the packet source switched off, which is one of the three degraded paths. */
    static @NotNull InputSettings withPacketSourceOff() {
        final InputSettings base = defaults();
        return new InputSettings(
                base.tuning(),
                false,
                base.actionCaptureEnabled(),
                base.pulseVisibilityNanos(),
                base.maxBindings(),
                base.faultLogIntervalNanos(),
                base.faultsBeforeDisable());
    }

    /**
     * A player that knows its identity and its name and nothing else.
     *
     * @param name the name a diagnostic would print
     * @return the player
     */
    static @NotNull Player player(@NotNull String name) {
        return player(name, UUID.randomUUID());
    }

    /**
     * A player with a chosen identity, for a test about a player-scoped binding.
     *
     * @param name     the name
     * @param playerId the identity, which is what every registry here is keyed on
     * @return the player
     */
    static @NotNull Player player(@NotNull String name, @NotNull UUID playerId) {
        return (Player) Proxy.newProxyInstance(
                InputFixtures.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return playerId;
                        case "getName":
                            return name;
                        case "hashCode":
                            return playerId.hashCode();
                        case "equals":
                            return proxy == (arguments == null ? null : arguments[0]);
                        case "toString":
                            return "FakePlayer[" + name + ']';
                        default:
                            // Anything else is the layer reading a player it is not allowed to read
                            // from an input thread. Failing loudly is the point of the fixture.
                            throw new UnsupportedOperationException(
                                    "The input layer must not call Player#" + method.getName());
                    }
                });
    }

    /**
     * A clock a test advances by hand.
     *
     * <p>Every window in the input layer is measured against this, so a test about a repeat interval or
     * a staleness window has to control it rather than sleep: a sleeping test is slow and, against a
     * 1.5-second window, flaky.
     */
    static final class ManualClock implements SimulationClock {

        private final @NotNull AtomicLong nanos = new AtomicLong();

        @Override
        public long nanoTime() {
            return this.nanos.get();
        }

        /** Moves the clock forward. */
        void advance(long byNanos) {
            this.nanos.addAndGet(byNanos);
        }

        /** Sets the clock. */
        void set(long toNanos) {
            this.nanos.set(toNanos);
        }
    }

    /**
     * A settings source that counts how many times it was read.
     *
     * <p>The fixture for the read-once discipline. A dispatcher that read the source per value rather
     * than per observation would pass every behavioural test in this package and still be able to judge
     * one input against two generations of configuration, so the count is the only thing that can
     * detect it.
     */
    static final class CountingSettingsSource implements InputSettingsSource {

        private volatile @NotNull InputSettings settings;
        private final @NotNull AtomicLong reads = new AtomicLong();

        CountingSettingsSource(@NotNull InputSettings settings) {
            this.settings = settings;
        }

        @Override
        public @NotNull InputSettings current() {
            this.reads.incrementAndGet();
            return this.settings;
        }

        /** How many times the source has been read. */
        long reads() {
            return this.reads.get();
        }

        /** Forgets the count, so a test can measure one operation rather than a whole set-up. */
        void resetCount() {
            this.reads.set(0L);
        }

        /** Publishes a new generation, as a reload would. */
        void publish(@NotNull InputSettings replacement) {
            this.settings = replacement;
        }
    }
}
