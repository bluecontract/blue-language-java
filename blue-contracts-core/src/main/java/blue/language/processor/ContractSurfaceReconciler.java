package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically reconciles exact effective bundles captured by the normal
 * contract traversal.
 *
 * <p>The service owns no parser. A caller creates two invocation-local
 * captures, feeds them while traversing the before and tentative roots, then
 * reconciles them with the production subscription delta.</p>
 */
final class ContractSurfaceReconciler {

    private final OperationRouteClassifier operationRoutes;

    ContractSurfaceReconciler(OperationRouteClassifier operationRoutes) {
        this.operationRoutes = Objects.requireNonNull(
                operationRoutes, "operationRoutes");
    }

    /** Creates one empty invocation-local capture. */
    Capture newCapture() {
        return new Capture();
    }

    /** Reconciles two complete captures without mutating either one. */
    ContractSurfaceReconciliation reconcile(
            String beforeRootIdentity,
            String afterRootIdentity,
            Capture before,
            Capture after,
            SubscriptionDelta subscriptions) {
        Capture input = Objects.requireNonNull(before, "before");
        Capture output = Objects.requireNonNull(after, "after");
        SubscriptionDelta subscriptionDelta = Objects.requireNonNull(
                subscriptions, "subscriptions");
        input.seal();
        output.seal();

        List<ContractSurfaceDelta.ContractOccurrenceDelta> contracts =
                new ArrayList<ContractSurfaceDelta.ContractOccurrenceDelta>();
        List<ContractSurfaceDelta.ChannelOccurrenceDelta> channels =
                new ArrayList<ContractSurfaceDelta.ChannelOccurrenceDelta>();
        List<ContractSurfaceDelta.OperationRouteDelta> operations =
                new ArrayList<ContractSurfaceDelta.OperationRouteDelta>();

        for (OccurrenceKey key : union(
                input.contracts.keySet(), output.contracts.keySet())) {
            ContractSurfaceDelta.ContractOccurrence prior =
                    input.contracts.get(key);
            ContractSurfaceDelta.ContractOccurrence current =
                    output.contracts.get(key);
            if (Objects.equals(prior, current)) {
                continue;
            }
            ContractSurfaceDelta.Change kind = change(prior, current);
            contracts.add(new ContractSurfaceDelta.ContractOccurrenceDelta(
                    kind, prior, current));
            addChannelDelta(channels, prior, current);
            addOperationDelta(operations, prior, current);
        }

        List<ContractSurfaceDelta.ProcessEmbeddedDeclarationDelta> embedded =
                new ArrayList<
                        ContractSurfaceDelta.ProcessEmbeddedDeclarationDelta>();
        for (OccurrenceKey key : union(
                input.processEmbedded.keySet(),
                output.processEmbedded.keySet())) {
            ContractSurfaceDelta.ProcessEmbeddedDeclaration prior =
                    input.processEmbedded.get(key);
            ContractSurfaceDelta.ProcessEmbeddedDeclaration current =
                    output.processEmbedded.get(key);
            if (!Objects.equals(prior, current)) {
                embedded.add(
                        new ContractSurfaceDelta
                                .ProcessEmbeddedDeclarationDelta(
                                change(prior, current), prior, current));
            }
        }

        ContractSurfaceDelta surface = new ContractSurfaceDelta(
                beforeRootIdentity,
                afterRootIdentity,
                contracts,
                channels,
                operations,
                embedded,
                subscriptionDeltas(subscriptionDelta));
        return new ContractSurfaceReconciliation(surface, subscriptionDelta);
    }

    private static void addChannelDelta(
            List<ContractSurfaceDelta.ChannelOccurrenceDelta> target,
            ContractSurfaceDelta.ContractOccurrence before,
            ContractSurfaceDelta.ContractOccurrence after) {
        boolean had = isChannel(before);
        boolean has = isChannel(after);
        if (had && has) {
            target.add(new ContractSurfaceDelta.ChannelOccurrenceDelta(
                    ContractSurfaceDelta.Change.REPLACE, before, after));
        } else {
            if (had) {
                target.add(new ContractSurfaceDelta.ChannelOccurrenceDelta(
                        ContractSurfaceDelta.Change.REMOVE, before, null));
            }
            if (has) {
                target.add(new ContractSurfaceDelta.ChannelOccurrenceDelta(
                        ContractSurfaceDelta.Change.ADD, null, after));
            }
        }
    }

    private void addOperationDelta(
            List<ContractSurfaceDelta.OperationRouteDelta> target,
            ContractSurfaceDelta.ContractOccurrence before,
            ContractSurfaceDelta.ContractOccurrence after) {
        boolean had = isOperation(before);
        boolean has = isOperation(after);
        if (had && has) {
            target.add(new ContractSurfaceDelta.OperationRouteDelta(
                    ContractSurfaceDelta.Change.REPLACE, before, after));
        } else {
            if (had) {
                target.add(new ContractSurfaceDelta.OperationRouteDelta(
                        ContractSurfaceDelta.Change.REMOVE, before, null));
            }
            if (has) {
                target.add(new ContractSurfaceDelta.OperationRouteDelta(
                        ContractSurfaceDelta.Change.ADD, null, after));
            }
        }
    }

    private boolean isOperation(
            ContractSurfaceDelta.ContractOccurrence occurrence) {
        return occurrence != null
                && EffectiveContractSnapshotConstants.Role.HANDLER.equals(
                        occurrence.role())
                && operationRoutes.isOperationRoute(
                        occurrence.effectiveTypeBlueId());
    }

    private static boolean isChannel(
            ContractSurfaceDelta.ContractOccurrence occurrence) {
        return occurrence != null
                && (EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL
                        .equals(occurrence.role())
                || EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                        .equals(occurrence.role()));
    }

    private static List<ContractSurfaceDelta.SubscriptionOccurrenceDelta>
    subscriptionDeltas(SubscriptionDelta delta) {
        Map<OccurrenceKey, SubscriptionDelta.Entry> removed =
                subscriptionsByOccurrence(delta.removed());
        Map<OccurrenceKey, SubscriptionDelta.Entry> added =
                subscriptionsByOccurrence(delta.added());
        List<ContractSurfaceDelta.SubscriptionOccurrenceDelta> result =
                new ArrayList<
                        ContractSurfaceDelta.SubscriptionOccurrenceDelta>();
        for (OccurrenceKey key : union(removed.keySet(), added.keySet())) {
            SubscriptionDelta.Entry before = removed.get(key);
            SubscriptionDelta.Entry after = added.get(key);
            result.add(
                    new ContractSurfaceDelta.SubscriptionOccurrenceDelta(
                            change(before, after), before, after));
        }
        return result;
    }

    private static Map<OccurrenceKey, SubscriptionDelta.Entry>
    subscriptionsByOccurrence(List<SubscriptionDelta.Entry> entries) {
        Map<OccurrenceKey, SubscriptionDelta.Entry> result =
                new LinkedHashMap<OccurrenceKey, SubscriptionDelta.Entry>();
        for (SubscriptionDelta.Entry entry : entries) {
            OccurrenceKey key = new OccurrenceKey(
                    entry.scopePath(), entry.channelKey());
            if (result.put(key, entry) != null) {
                throw new IllegalArgumentException(
                        "Duplicate subscription occurrence in delta");
            }
        }
        return result;
    }

    private static <T> ContractSurfaceDelta.Change change(
            T before,
            T after) {
        return before == null
                ? ContractSurfaceDelta.Change.ADD
                : after == null
                ? ContractSurfaceDelta.Change.REMOVE
                : ContractSurfaceDelta.Change.REPLACE;
    }

    private static List<OccurrenceKey> union(
            Set<OccurrenceKey> before,
            Set<OccurrenceKey> after) {
        Set<OccurrenceKey> values = new LinkedHashSet<OccurrenceKey>();
        values.addAll(before);
        values.addAll(after);
        List<OccurrenceKey> ordered = new ArrayList<OccurrenceKey>(values);
        Collections.sort(ordered);
        return ordered;
    }

    /** Frozen capability supplied by the invocation's registry generation. */
    interface OperationRouteClassifier {
        boolean isOperationRoute(String effectiveTypeBlueId);
    }

    /** One mutable invocation-local traversal sink, frozen by reconciliation. */
    static final class Capture implements ContractSurfaceCollector {
        private final Map<OccurrenceKey,
                ContractSurfaceDelta.ContractOccurrence> contracts =
                new LinkedHashMap<OccurrenceKey,
                        ContractSurfaceDelta.ContractOccurrence>();
        private final Map<OccurrenceKey,
                ContractSurfaceDelta.ProcessEmbeddedDeclaration>
                processEmbedded = new LinkedHashMap<OccurrenceKey,
                        ContractSurfaceDelta.ProcessEmbeddedDeclaration>();
        private boolean sealed;

        @Override
        public void record(
                String scopePath,
                ContractBundle bundle) {
            String scope = Objects.requireNonNull(scopePath, "scopePath");
            if (sealed) {
                throw new IllegalStateException(
                        "A reconciled contract-surface capture is sealed");
            }
            ContractBundle exactBundle = Objects.requireNonNull(
                    bundle, "bundle");
            ContractSurfaceDelta.ContractOccurrence embedded = null;
            for (EffectiveContractSnapshot snapshot
                    : exactBundle.effectiveContractSnapshots()) {
                if (!scope.equals(snapshot.scopePath())) {
                    throw new IllegalStateException(
                            "Effective contract scope does not match traversal");
                }
                ContractSurfaceDelta.ContractOccurrence occurrence =
                        ContractSurfaceDelta.ContractOccurrence.from(snapshot);
                OccurrenceKey key = new OccurrenceKey(
                        occurrence.scopePath(), occurrence.key());
                ContractSurfaceDelta.ContractOccurrence previous =
                        contracts.get(key);
                if (previous != null && !previous.equals(occurrence)) {
                    throw new IllegalStateException(
                            "Conflicting effective contract occurrence at "
                                    + scope + "/" + occurrence.key());
                }
                if (previous == null) {
                    contracts.put(key, occurrence);
                }
                if (EffectiveContractSnapshotConstants.Role.PROCESS_EMBEDDED
                        .equals(occurrence.role())) {
                    if (embedded != null) {
                        throw new IllegalStateException(
                                "Multiple effective Process Embedded contracts at "
                                        + scope);
                    }
                    embedded = occurrence;
                }
            }
            if (embedded == null) {
                return;
            }
            EmbeddedScopeDeclaration declaration =
                    exactBundle.embeddedScopeDeclaration();
            OccurrenceKey key = new OccurrenceKey(
                    embedded.scopePath(), embedded.key());
            ContractSurfaceDelta.ProcessEmbeddedDeclaration evidence =
                    new ContractSurfaceDelta.ProcessEmbeddedDeclaration(
                            embedded,
                            declaration.explicitPaths(),
                            declaration.collectionPaths());
            ContractSurfaceDelta.ProcessEmbeddedDeclaration previous =
                    processEmbedded.get(key);
            if (previous != null && !previous.equals(evidence)) {
                throw new IllegalStateException(
                        "Conflicting Process Embedded declaration at "
                                + scope + "/" + embedded.key());
            }
            if (previous == null) {
                processEmbedded.put(key, evidence);
            }
        }

        private void seal() {
            sealed = true;
        }
    }

    /** Canonical two-part occurrence key; never a concatenated identity. */
    private static final class OccurrenceKey
            implements Comparable<OccurrenceKey> {
        private final String scopePath;
        private final String key;

        private OccurrenceKey(String scopePath, String key) {
            this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
            this.key = Objects.requireNonNull(key, "key");
        }

        @Override
        public int compareTo(OccurrenceKey other) {
            int order = ExternalOrderKey.compareTextCodePoints(
                    scopePath, other.scopePath);
            return order != 0
                    ? order
                    : ExternalOrderKey.compareTextCodePoints(key, other.key);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof OccurrenceKey)) {
                return false;
            }
            OccurrenceKey that = (OccurrenceKey) other;
            return scopePath.equals(that.scopePath) && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scopePath, key);
        }
    }
}
