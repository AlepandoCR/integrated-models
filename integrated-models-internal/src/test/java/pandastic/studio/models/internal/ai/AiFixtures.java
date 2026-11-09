package pandastic.studio.models.internal.ai;

import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiActivityPlan;
import pandastic.studio.models.api.ai.AiBehaviour;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.AiSensor;

import java.util.Set;

/**
 * The plan parts every suite in this package needs, built once and named.
 *
 * <h2>Why the api's own tests live in the internal module</h2>
 * Because {@code AiContext} names {@code org.bukkit.entity.Mob}, and the api module's test source set
 * has no platform on its classpath — {@code paper-api} is {@code compileOnly} for every module, which
 * does not reach a test source set, and the api module deliberately declares no dependencies of its
 * own. A test touching anything that mentions a platform type therefore cannot even link there.
 *
 * <p>The internal module's test source set does have it, so the suites run here unchanged and against
 * the published types rather than against anything internal. That is the whole of the compromise: the
 * subject is the api, the classpath is the internal module's.
 *
 * <h2>Why the bounds here are not the shipped defaults</h2>
 * They are deliberately small and deliberately odd. A fixture built from the values {@code ai.yml}
 * ships would make every bound test pass for a plan that happens to be smaller than the default, which
 * proves the plan is small rather than that the validator read the bounds. Numbers nothing would choose
 * are what distinguish the two.
 */
final class AiFixtures {

    /** Small, odd, and nothing like the shipped defaults. See the class documentation. */
    static final AiBounds BOUNDS = new AiBounds(3, 2, 7, 2, 2L, 40L, 11.0D, 500L, 8);

    private AiFixtures() {
    }

    /** A behaviour that does nothing, declares nothing, and can always start. */
    static AiBehaviour behaviour(String name) {
        return new AiBehaviour() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean canStart(AiContext context) {
                return true;
            }

            @Override
            public void tick(AiContext context, long ticksRunning) {
                // Intentionally empty; this fixture exists to be validated, not to run.
            }
        };
    }

    /** A behaviour that requires {@code required} and uses {@code used}. */
    static AiBehaviour behaviour(String name, Set<AiMemory<?>> required, Set<AiMemory<?>> used) {
        return new AiBehaviour() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<AiMemory<?>> requiredMemories() {
                return required;
            }

            @Override
            public Set<AiMemory<?>> usedMemories() {
                return used;
            }

            @Override
            public boolean canStart(AiContext context) {
                return true;
            }

            @Override
            public void tick(AiContext context, long ticksRunning) {
                // Intentionally empty.
            }
        };
    }

    /** A sensor that writes {@code written} and senses nothing. */
    static AiSensor sensor(String name, Set<AiMemory<?>> written) {
        return new AiSensor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<AiMemory<?>> writtenMemories() {
                return written;
            }

            @Override
            public void sense(AiContext context) {
                // Intentionally empty.
            }
        };
    }

    /** One filled slot holding one trivial behaviour. */
    static AiActivityPlan activity(AiActivity activity) {
        return AiActivityPlan.builder(activity)
                .behaviour(0, behaviour(activity.vanillaName() + "-body"))
                .build();
    }

    /** The smallest plan that builds: one slot, named as the default. */
    static AiPlan.Builder minimal(String name) {
        return AiPlan.builder(name, BOUNDS)
                .activity(activity(AiActivity.IDLE))
                .defaultActivity(AiActivity.IDLE);
    }
}
