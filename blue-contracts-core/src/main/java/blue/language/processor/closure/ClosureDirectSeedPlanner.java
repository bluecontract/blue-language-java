package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the canonical execution order for frozen external direct seeds. */
final class ClosureDirectSeedPlanner {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private ClosureDirectSeedPlanner() {
    }

    /**
     * Materializes one exact {@link WorkKind#EXTERNAL_DELIVERY} occurrence for
     * every frozen logical delivery.
     *
     * <p>The delivery snapshot remains in raw-source order. Execution uses a
     * separate component-first order so a target component always settles
     * before a source component that embeds it.</p>
     */
    static List<ClosureWorkOccurrence> plan(
            String invocationIdentity,
            String eventBlueId,
            List<ComponentSnapshot> components,
            List<DirectLogicalDelivery> deliveries) {
        final String invocation = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
        final String event = ClosureValueSupport.requireBlueId(
                eventBlueId, "eventBlueId");
        final Map<DocumentId, Integer> componentRanks = componentRanks(
                components);
        ArrayList<DirectLogicalDelivery> ordered =
                new ArrayList<DirectLogicalDelivery>(
                        Objects.requireNonNull(deliveries, "deliveries"));
        for (DirectLogicalDelivery delivery : ordered) {
            Objects.requireNonNull(delivery, "delivery");
            if (!componentRanks.containsKey(delivery.targetDocumentId())) {
                throw new IllegalArgumentException(
                        "Direct delivery target is outside the component partition");
            }
        }
        Collections.sort(ordered, new Comparator<DirectLogicalDelivery>() {
            @Override
            public int compare(
                    DirectLogicalDelivery left,
                    DirectLogicalDelivery right) {
                int order = Integer.compare(
                        componentRanks.get(left.targetDocumentId()).intValue(),
                        componentRanks.get(right.targetDocumentId()).intValue());
                if (order != 0) {
                    return order;
                }
                order = left.targetDocumentId().compareTo(
                        right.targetDocumentId());
                if (order != 0) {
                    return order;
                }
                order = left.targetScope().compareTo(right.targetScope());
                if (order != 0) {
                    return order;
                }
                order = ClosureValueSupport.comparePortableText(
                        left.channelKey(), right.channelKey());
                if (order != 0) {
                    return order;
                }
                order = ClosureValueSupport.comparePortableText(
                        left.logicalDeliveryKey(),
                        right.logicalDeliveryKey());
                return order != 0
                        ? order
                        : Long.compare(
                                left.rawOccurrenceOrder(),
                                right.rawOccurrenceOrder());
            }
        });

        ArrayList<ClosureWorkOccurrence> work =
                new ArrayList<ClosureWorkOccurrence>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            DirectLogicalDelivery delivery = ordered.get(index);
            String scopeIdentity = IDENTITIES.managedScopeKeyIdentity(
                    delivery.targetScope());
            String sourceIdentity = IDENTITIES.directDeliveryIdentity(
                    delivery);
            String workIdentity = IDENTITIES.workOccurrenceIdentity(
                    invocation,
                    index,
                    WorkKind.EXTERNAL_DELIVERY,
                    scopeIdentity,
                    sourceIdentity);
            work.add(new ClosureWorkOccurrence(
                    index,
                    WorkKind.EXTERNAL_DELIVERY,
                    delivery.targetDocumentId(),
                    delivery.channelKey(),
                    event,
                    Long.valueOf(0L),
                    scopeIdentity,
                    sourceIdentity,
                    workIdentity));
        }
        return Collections.unmodifiableList(work);
    }

    private static Map<DocumentId, Integer> componentRanks(
            List<ComponentSnapshot> components) {
        HashMap<DocumentId, Integer> result =
                new HashMap<DocumentId, Integer>();
        List<ComponentSnapshot> exact = Objects.requireNonNull(
                components, "components");
        for (int rank = 0; rank < exact.size(); rank++) {
            ComponentSnapshot component = Objects.requireNonNull(
                    exact.get(rank), "component");
            for (DocumentId member
                    : component.orderedMemberDocumentIds()) {
                if (result.put(member, Integer.valueOf(rank)) != null) {
                    throw new IllegalArgumentException(
                            "Component partition repeats a managed document");
                }
            }
        }
        return result;
    }
}
