package pandastic.studio.models.internal.ai;

import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.internal.nms.MemoryStore;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A memory store with no brain behind it: a map, and a record of what was asked of it.
 *
 * <h2>Why it is deliberately not defensive</h2>
 * It accepts every key, including ones the plan does not declare, and it never throws. That is the
 * point: the policy under test is {@link GuardedMemoryView}'s, so a store that refused anything would
 * make it impossible to tell whether the guard refused a read or the store did. If a test sees a value
 * in {@link #written} that the plan does not declare, the guard let something through.
 */
final class FakeMemoryStore implements MemoryStore {

    /** Current values, by api key. Insertion-ordered so a test can assert on write order. */
    final Map<String, Object> values = new LinkedHashMap<>();

    /** Expiry supplied with each write, by api key. Overwritten on each write, like the value. */
    final Map<String, Long> expiries = new HashMap<>();

    /** Every key written, in order, including repeats. Distinguishes "no write" from "overwritten". */
    final Map<String, Integer> written = new LinkedHashMap<>();

    /** Every key erased, in order. */
    final Map<String, Integer> erased = new LinkedHashMap<>();

    @Override
    public Object read(AiMemory<?> memory) {
        return this.values.get(memory.getKey());
    }

    @Override
    public void write(AiMemory<?> memory, Object value, long expiryTicks) {
        this.values.put(memory.getKey(), value);
        this.expiries.put(memory.getKey(), expiryTicks);
        this.written.merge(memory.getKey(), 1, Integer::sum);
    }

    @Override
    public boolean present(AiMemory<?> memory) {
        return this.values.containsKey(memory.getKey());
    }

    @Override
    public void erase(AiMemory<?> memory) {
        this.values.remove(memory.getKey());
        this.expiries.remove(memory.getKey());
        this.erased.merge(memory.getKey(), 1, Integer::sum);
    }
}
