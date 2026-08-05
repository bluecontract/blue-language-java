package blue.language.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a canonical subscription delta from before/after surface values.
 */
final class SubscriptionDeltaBuilder {

    private final ActivationIntervalValidator intervals;

    SubscriptionDeltaBuilder(ActivationIntervalValidator intervals) {
        this.intervals = intervals;
    }

    /**
     * Compares occurrences by key and immutable subscription snapshot, then
     * applies commit interval bounds to replacements.
     */
    SubscriptionDelta build(
            Map<String, SubscriptionDelta.Entry> before,
            Map<String, SubscriptionDelta.Entry> after,
            SubscriptionSurfaceValidationContext context) {
        List<SubscriptionDelta.Entry> removed = new ArrayList<>();
        List<SubscriptionDelta.Entry> added = new ArrayList<>();
        for (Map.Entry<String, SubscriptionDelta.Entry> entry
                : before.entrySet()) {
            SubscriptionDelta.Entry replacement = after.get(entry.getKey());
            if (context.replacesOccurrence(
                    entry.getValue().scopePath())
                    || !entry.getValue()
                            .sameSubscriptionSnapshot(replacement)) {
                removed.add(intervals.retire(entry.getValue(), context));
            }
        }
        for (Map.Entry<String, SubscriptionDelta.Entry> entry
                : after.entrySet()) {
            SubscriptionDelta.Entry previous = before.get(entry.getKey());
            if (context.replacesOccurrence(
                    entry.getValue().scopePath())
                    || !entry.getValue()
                            .sameSubscriptionSnapshot(previous)) {
                added.add(intervals.activate(entry.getValue(), context));
            }
        }
        return new SubscriptionDelta(added, removed);
    }
}
