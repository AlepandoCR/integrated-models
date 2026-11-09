package pandastic.studio.models.internal.sim;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.PhysicsDriver;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverHolderTest {

    private final DriverHolder holder = new DriverHolder();

    @Test
    void defaultsToAMotionlessOwnerBeforeTheFirstSample() {
        // The loop and the per-entity sampler start independently, so a simulation will step before
        // any sample exists. Returning null here would put a null check in the hot path and an NPE in
        // every solver that forgot one.
        assertSame(PhysicsDriver.STILL, this.holder.current());
    }

    @Test
    void publishesTheMostRecentSample() {
        final PhysicsDriver first = new PhysicsDriver(0.2F, 0F, 0F, 0F);
        final PhysicsDriver second = new PhysicsDriver(0F, 0.1F, -0.4F, 0.03F);

        this.holder.publish(first);
        assertSame(first, this.holder.current());

        // Last writer wins: a sample is a snapshot, not an event, and a stale one is of no interest
        // to a solver asking how the owner is moving now.
        this.holder.publish(second);
        assertSame(second, this.holder.current());
    }

    @Test
    void carriesASampleAcrossAThreadBoundaryWhole() throws InterruptedException {
        final PhysicsDriver sampled = new PhysicsDriver(0.21F, -0.05F, 0.4F, 0.11F);
        final AtomicReference<PhysicsDriver> observed = new AtomicReference<>();
        final CountDownLatch published = new CountDownLatch(1);
        final CountDownLatch read = new CountDownLatch(1);

        // Stands in for the owning region thread, which is the only thread allowed to have read the
        // entity these numbers came from.
        final Thread region = new Thread(() -> {
            this.holder.publish(sampled);
            published.countDown();
        }, "fake-region-thread");
        final Thread simulation = new Thread(() -> {
            try {
                if (published.await(10L, TimeUnit.SECONDS)) {
                    observed.set(this.holder.current());
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                read.countDown();
            }
        }, "fake-simulation-thread");

        simulation.start();
        region.start();
        assertTrue(read.await(10L, TimeUnit.SECONDS), "the reader never ran");
        region.join();
        simulation.join();

        assertAll(
                () -> assertSame(sampled, observed.get()),
                // All four fields come from the same sample. A mutable holder written field by field
                // would publish this tick's forward speed against last tick's yaw rate, which reads
                // as a bone twitching for no reproducible reason.
                () -> assertSame(sampled, this.holder.current()));
    }

    @Test
    void theSeamIsAVolatileReferenceStore() {
        // A memory-model property no amount of exercising can observe, so it is asserted structurally
        // instead: the single reference store is what carries the happens-before edge that makes the
        // record's four fields visible together.
        final Field field = assertDoesNotThrow(() -> DriverHolder.class.getDeclaredField("published"));
        assertAll(
                () -> assertTrue(Modifier.isVolatile(field.getModifiers()),
                        "the published reference must be volatile"),
                () -> assertSame(PhysicsDriver.class, field.getType(),
                        "it must hold an immutable value, not a mutable holder"),
                () -> assertTrue(field.getType().isRecord(),
                        "immutability is what makes a single store sufficient"));
    }

    @Test
    void resetForgetsAStaleSample() {
        this.holder.publish(new PhysicsDriver(0.2F, 0F, 0F, 0F));
        this.holder.reset();

        // An unbound model would otherwise keep being dragged by movement that stopped happening.
        assertSame(PhysicsDriver.STILL, this.holder.current());
    }

    @Test
    void refusesANullSample() {
        assertThrows(NullPointerException.class, () -> this.holder.publish(null));
    }
}
