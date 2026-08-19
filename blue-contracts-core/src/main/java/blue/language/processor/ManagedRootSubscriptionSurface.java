package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Exact, non-recursive subscription projection of one independently managed
 * Root.
 *
 * <p>The complete Channel occurrence list is the same Root-only evidence used
 * by closure result assembly. The external subscription descriptors add the
 * resolved routing keys, checkpoint domain, and deterministic dependencies
 * needed by a host index. No embedded scope is opened or projected.</p>
 */
public final class ManagedRootSubscriptionSurface {
    private final List<ManagedRootChannelOccurrence> channelOccurrences;
    private final List<SubscriptionDelta.Entry> externalSubscriptions;
    private final List<EffectiveContractSnapshot> effectiveRootContracts;

    ManagedRootSubscriptionSurface(
            List<ManagedRootChannelOccurrence> channelOccurrences,
            List<SubscriptionDelta.Entry> externalSubscriptions,
            List<EffectiveContractSnapshot> effectiveRootContracts) {
        this.channelOccurrences = immutable(
                channelOccurrences, "channelOccurrence");
        this.externalSubscriptions = immutable(
                externalSubscriptions, "externalSubscription");
        this.effectiveRootContracts = immutable(
                effectiveRootContracts, "effectiveRootContract");
    }

    /**
     * Returns every effective Root Channel, including processor-only Channels.
     *
     * @return immutable canonical Channel occurrence list
     */
    public List<ManagedRootChannelOccurrence> channelOccurrences() {
        return channelOccurrences;
    }

    /**
     * Returns only externally routable Root subscriptions without intervals.
     *
     * @return immutable canonical external subscription descriptors
     */
    public List<SubscriptionDelta.Entry> externalSubscriptions() {
        return externalSubscriptions;
    }

    /**
     * Returns the exact effective contracts of this Root in deterministic
     * dispatch order.
     *
     * <p>This non-recursive view lets a host compile operation routes for an
     * independently managed cyclic member without reopening that member as an
     * ordinary fragmentation-catalog Root.</p>
     *
     * @return immutable Root-only effective contract snapshots
     */
    public List<EffectiveContractSnapshot> effectiveRootContracts() {
        return effectiveRootContracts;
    }

    private static <T> List<T> immutable(
            List<T> values,
            String label) {
        List<T> result = new ArrayList<T>();
        for (T value : Objects.requireNonNull(values, label + "s")) {
            result.add(Objects.requireNonNull(value, label));
        }
        return Collections.unmodifiableList(result);
    }
}
