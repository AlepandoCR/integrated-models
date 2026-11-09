package pandastic.studio.models.internal;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiPoint;
import pandastic.studio.models.internal.ai.AiHost;
import pandastic.studio.models.internal.ai.AiTask;
import pandastic.studio.models.internal.nms.BrainBridge;
import pandastic.studio.models.internal.nms.BrainInstallRequest;
import pandastic.studio.models.internal.nms.BrainInstallation;
import pandastic.studio.models.internal.nms.MemoryStore;
import pandastic.studio.models.internal.nms.MovementBridge;
import pandastic.studio.models.internal.nms.NavigationBridge;
import pandastic.studio.models.internal.nms.NmsBridge;
import pandastic.studio.models.internal.nms.RiderInputBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole NMS seam, as a double: an adapter that installs brains into a map and records what it was
 * asked to do.
 *
 * <h2>Why the AI layer needs a double here at all</h2>
 * Because {@link pandastic.studio.models.internal.ai.AiRuntime#shutdown()} is one of the five hooks the
 * runtime's shutdown must reach, and it is the only one whose effect is invisible unless something has
 * actually been installed: with no adapter it returns zero and touches nothing, so a test built on an
 * unavailable AI layer cannot tell a runtime that calls it from one that forgot. One installed plan is
 * what turns that hook into a recorded event.
 *
 * <p>It proves nothing about the brain rebuild — there is no brain here, no registry and no memory slot
 * keys, because none of that exists without a bootstrapped server. That is on the list of things only a
 * live server can confirm, and faking it would produce evidence about our idea of the server rather than
 * about the server.
 */
final class RecordingBrains implements BrainBridge {

    private final @NotNull RuntimeFixtures.Journal journal;

    /** Every installation handed out, in order, so a test can check which ones were uninstalled. */
    private final @NotNull List<Installation> installations = new ArrayList<>();

    RecordingBrains(@NotNull RuntimeFixtures.Journal journal) {
        this.journal = journal;
    }

    @Override
    public boolean supports(@NotNull Mob mob) {
        return true;
    }

    @Override
    public @NotNull List<String> supportedSpecies() {
        return List.of("Villager");
    }

    @Override
    public @NotNull BrainInstallation install(@NotNull BrainInstallRequest request) {
        this.journal.record("ai-install");
        final Installation installation = new Installation();
        this.installations.add(installation);
        return installation;
    }

    /** How many installations are still installed. Zero after the runtime's shutdown reached the layer. */
    int liveInstallations() {
        return (int) this.installations.stream().filter(BrainInstallation::isInstalled).count();
    }

    /** This bridge wrapped as the one seam the lookup would have produced. */
    @NotNull NmsBridge bridge() {
        return new NmsBridge() {

            @Override
            public @NotNull String minecraftVersion() {
                return "test";
            }

            @Override
            public @NotNull BrainBridge brains() {
                return RecordingBrains.this;
            }

            @Override
            public @NotNull NavigationBridge navigation() {
                // A marker interface with no members; there is nothing to implement.
                return new NavigationBridge() {
                };
            }

            @Override
            public @NotNull RiderInputBridge riderInput() {
                return new RiderInputBridge() {
                };
            }

            @Override
            public @NotNull MovementBridge movement() {
                return new MovementBridge() {
                };
            }
        };
    }

    /** A per-creature host that runs everything inline on the calling thread. */
    @NotNull AiHost hostFor(@NotNull Mob mob) {
        return new Host(mob);
    }

    /** An installation that records its one teardown. */
    private final class Installation implements BrainInstallation {

        private final @NotNull Map<AiMemory<?>, Object> values = new HashMap<>();
        private boolean installed = true;

        @Override
        public @NotNull MemoryStore memories() {
            return new MemoryStore() {

                @Override
                public @Nullable Object read(@NotNull AiMemory<?> memory) {
                    return Installation.this.values.get(memory);
                }

                @Override
                public void write(
                        @NotNull AiMemory<?> memory,
                        @NotNull Object value,
                        long expiryTicks
                ) {
                    Installation.this.values.put(memory, value);
                }

                @Override
                public boolean present(@NotNull AiMemory<?> memory) {
                    return Installation.this.values.containsKey(memory);
                }

                @Override
                public void erase(@NotNull AiMemory<?> memory) {
                    Installation.this.values.remove(memory);
                }
            };
        }

        @Override
        public boolean isInstalled() {
            return this.installed;
        }

        @Override
        public void uninstall() {
            if (!this.installed) {
                return;
            }
            this.installed = false;
            RecordingBrains.this.journal.record("ai-uninstall");
        }

        @Override
        public @NotNull String describe() {
            return "RecordingInstallation";
        }
    }

    /**
     * A creature seam that is alive, owned by whichever thread asks, and runs deferred work inline.
     *
     * <p>Inline submission is what makes an install observable in a test: the real one hops to the
     * creature's scheduler, and the whole touching half of an install happens in that hop.
     */
    private final class Host implements AiHost {

        private final @NotNull Mob mob;
        private final @NotNull UUID creatureId = RuntimeFixtures.id();

        private Host(@NotNull Mob mob) {
            this.mob = mob;
        }

        @Override
        public @NotNull Mob mob() {
            return this.mob;
        }

        @Override
        public @NotNull UUID creatureId() {
            return this.creatureId;
        }

        @Override
        public boolean isOwnedByCurrentThread() {
            return true;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public @NotNull AiPoint position() {
            return AiPoint.ORIGIN;
        }

        @Override
        public long gameTime() {
            return 0L;
        }

        @Override
        public boolean submit(@NotNull Runnable work) {
            work.run();
            return true;
        }

        @Override
        public @Nullable AiTask repeat(
                @NotNull Runnable body,
                @NotNull Runnable retired,
                long periodTicks
        ) {
            RecordingBrains.this.journal.record("ai-sensor");
            return new AiTask() {

                private boolean cancelled;

                @Override
                public void cancel() {
                    this.cancelled = true;
                }

                @Override
                public boolean isCancelled() {
                    return this.cancelled;
                }
            };
        }

        @Override
        public double ownedRadius(double requestedBlocks) {
            return requestedBlocks;
        }

        @Override
        public @NotNull List<Entity> entitiesWithin(double radiusBlocks) {
            return List.of();
        }

        @Override
        public @NotNull List<Player> playersWithin(double radiusBlocks) {
            return List.of();
        }

        @Override
        public @NotNull String describe() {
            return "RecordingHost";
        }
    }
}
