package pandastic.studio.models.internal;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.decoration.DecorationAnchor;
import pandastic.studio.models.internal.decoration.DecorationRendering;

import java.util.UUID;

/**
 * A world anchor that records its lifecycle events under a name.
 *
 * <p>The decoration layer's counterpart to {@link RecordingEntityAnchor}, and the same contract: it
 * reports the calling thread as the owning one, because which thread may place a decoration belongs to
 * {@code DecorationImplTest} rather than here. What it is for is making "a decoration the runtime
 * handed out was released on disable" a recorded event.
 */
final class RecordingWorldAnchor implements DecorationAnchor {

    private final @NotNull RuntimeFixtures.Journal journal;
    private final @NotNull String name;
    private final @NotNull UUID worldId = RuntimeFixtures.id();

    RecordingWorldAnchor(@NotNull RuntimeFixtures.Journal journal, @NotNull String name) {
        this.journal = journal;
        this.name = name;
    }

    @Override
    public @NotNull UUID worldId() {
        return this.worldId;
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull DecorationPlacement placement) {
        return true;
    }

    @Override
    public boolean submit(@NotNull DecorationPlacement placement, @NotNull Runnable work) {
        work.run();
        return true;
    }

    @Override
    public @NotNull DecorationRendering open(
            @NotNull Rig rig,
            @NotNull DecorationPlacement placement
    ) {
        this.journal.record(this.name + "-open");
        return new Rendering();
    }

    @Override
    public @NotNull String describe() {
        return this.name;
    }

    /** A renderer-side binding that records the two steps of its release. */
    private final class Rendering implements DecorationRendering {

        private boolean closed;

        @Override
        public void moveTo(@NotNull DecorationPlacement placement) {
            // Intentionally empty: these tests never move a decoration.
        }

        @Override
        public boolean show(@NotNull UUID viewer) {
            return true;
        }

        @Override
        public boolean hide(@NotNull UUID viewer) {
            return true;
        }

        @Override
        public @NotNull PoseSink sink() {
            return (Rig rig, Pose pose, int interpolationTicks) -> {
                // Intentionally empty: these tests never step a simulation.
            };
        }

        @Override
        public void detach() {
            RecordingWorldAnchor.this.journal.record(RecordingWorldAnchor.this.name + "-detach");
        }

        @Override
        public void close() {
            this.closed = true;
            RecordingWorldAnchor.this.journal.record(RecordingWorldAnchor.this.name + "-close");
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }
    }
}
