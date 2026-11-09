package pandastic.studio.models.api.ai;

/**
 * What a memory has to look like for an activity to be allowed to run.
 *
 * <h2>Why conditions exist at all, when a behaviour could just check</h2>
 * Because a condition is checked <em>before</em> the activity's behaviours are considered, once, rather
 * than by every behaviour in it. An activity with eight behaviours and one condition does one check;
 * the same logic written into each behaviour's start test does eight, every tick, and goes wrong the
 * first time someone adds a ninth behaviour and forgets. It is also the only mechanism that can stop an
 * activity that is already running — which is what makes "leave combat when the target is gone" a
 * property of the activity rather than something every combat behaviour has to agree about.
 *
 * <p>The three constants mirror the server's own, because the condition is evaluated by the server
 * inside the brain's tick and this is the vocabulary it evaluates in. Mapping onto a different set here
 * would mean translating, and a translation that loses {@link #REGISTERED} would leave no way to say
 * "the creature has this memory at all, whatever is in it".
 */
public enum AiMemoryStatus {

    /** The memory holds a value right now. */
    VALUE_PRESENT,

    /**
     * The memory holds no value right now.
     *
     * <p>The one most plans need, and the easiest to get backwards: an activity gated on
     * {@code VALUE_ABSENT} of a cooldown memory runs only while the cooldown is <em>not</em> set.
     */
    VALUE_ABSENT,

    /**
     * The creature has this memory at all, whether or not it holds a value.
     *
     * <p><strong>Always true inside this layer</strong>, and that is worth stating rather than leaving
     * to be discovered: a plan's brain is built with exactly the memories the plan declares, including
     * its {@link AiMemory#vanilla(String)} references, so declaring one is what makes it registered. A
     * condition of this kind therefore never gates anything.
     *
     * <p>It exists because the server's condition vocabulary has three values and a plan that could
     * only express two of them would be unable to say something the server supports. Treat it as
     * documentation of intent — "this activity is about that memory" — rather than as a test.
     */
    REGISTERED
}
