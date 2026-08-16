package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete closure result; resource acquisition is modeled by the separate Attempt union. */
public final class ClosureProcessResult {
    private final ClosureProcessStatus status;
    private final String invocationIdentity;
    private final String inputClosureIdentity;
    private final String outputClosureIdentity;
    private final long graphGeneration;
    private final List<ResultingDocument> resultingDocuments;
    private final List<ResultingComponent> resultingComponents;
    private final List<ManagedOccurrenceBinding> occurrenceBindings;
    private final String occurrenceBindingSetIdentity;
    private final List<GraphChange> graphChanges;
    private final String graphChangesIdentity;
    private final List<SubscriptionDelta> subscriptionDeltas;
    private final String subscriptionDeltasIdentity;
    private final List<CheckpointWrite> checkpointWrites;
    private final String checkpointWritesIdentity;
    private final List<PublicEventOccurrence> publicEvents;
    private final String publicEventsIdentity;
    private final long totalGas;
    private final List<GasCharge> gasTrace;
    private final String gasTraceIdentity;
    private final SharedGasMeter.RejectedCharge rejectedCharge;
    private final WorkOccurrence rejectedWorkOccurrence;
    private final ClosureCommitPlan platformCommitCompanion;
    private final String diagnostic;

    public ClosureProcessResult(
            ClosureProcessStatus status,
            String invocationIdentity,
            String inputClosureIdentity,
            String outputClosureIdentity,
            long graphGeneration,
            List<ResultingDocument> resultingDocuments,
            List<ResultingComponent> resultingComponents,
            List<ManagedOccurrenceBinding> occurrenceBindings,
            String occurrenceBindingSetIdentity,
            List<GraphChange> graphChanges,
            String graphChangesIdentity,
            List<SubscriptionDelta> subscriptionDeltas,
            String subscriptionDeltasIdentity,
            List<CheckpointWrite> checkpointWrites,
            String checkpointWritesIdentity,
            List<PublicEventOccurrence> publicEvents,
            String publicEventsIdentity,
            long totalGas,
            List<GasCharge> gasTrace,
            String gasTraceIdentity,
            SharedGasMeter.RejectedCharge rejectedCharge,
            WorkOccurrence rejectedWorkOccurrence,
            ClosureCommitPlan platformCommitCompanion,
            String diagnostic) {
        this.status = Objects.requireNonNull(status, "status");
        this.invocationIdentity = Objects.requireNonNull(invocationIdentity, "invocationIdentity");
        this.inputClosureIdentity = Objects.requireNonNull(
                inputClosureIdentity, "inputClosureIdentity");
        this.outputClosureIdentity = Objects.requireNonNull(
                outputClosureIdentity, "outputClosureIdentity");
        this.graphGeneration = CanonicalOrders.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.resultingDocuments = immutableList(resultingDocuments, "resultingDocuments");
        this.resultingComponents = immutableList(resultingComponents, "resultingComponents");
        this.occurrenceBindings = immutableList(occurrenceBindings, "occurrenceBindings");
        this.occurrenceBindingSetIdentity = Objects.requireNonNull(
                occurrenceBindingSetIdentity, "occurrenceBindingSetIdentity");
        this.graphChanges = immutableList(graphChanges, "graphChanges");
        this.graphChangesIdentity = Objects.requireNonNull(
                graphChangesIdentity, "graphChangesIdentity");
        this.subscriptionDeltas = immutableList(subscriptionDeltas, "subscriptionDeltas");
        this.subscriptionDeltasIdentity = Objects.requireNonNull(
                subscriptionDeltasIdentity, "subscriptionDeltasIdentity");
        this.checkpointWrites = immutableList(checkpointWrites, "checkpointWrites");
        this.checkpointWritesIdentity = Objects.requireNonNull(
                checkpointWritesIdentity, "checkpointWritesIdentity");
        this.publicEvents = immutableList(publicEvents, "publicEvents");
        this.publicEventsIdentity = Objects.requireNonNull(
                publicEventsIdentity, "publicEventsIdentity");
        this.totalGas = CanonicalOrders.requireSafeInteger(totalGas, "totalGas");
        this.gasTrace = immutableList(gasTrace, "gasTrace");
        this.gasTraceIdentity = Objects.requireNonNull(gasTraceIdentity, "gasTraceIdentity");
        this.rejectedCharge = rejectedCharge;
        this.rejectedWorkOccurrence = rejectedWorkOccurrence;
        this.platformCommitCompanion = platformCommitCompanion;
        this.diagnostic = diagnostic;
        validateCanonicalCollections();
        validateStatusShape();
    }

    private void validateCanonicalCollections() {
        requireDocumentOrder(resultingDocuments);
        requireOccurrenceOrder(occurrenceBindings);
        requireGraphChangeOrdinals(graphChanges);
        requireSubscriptionDeltaOrdinals(subscriptionDeltas);
        requireCheckpointWriteOrdinals(checkpointWrites);
        requirePublicEventOrdinals(publicEvents);
        requireGasTrace(gasTrace, totalGas);
        requireCompleteComponentMembership(resultingDocuments, resultingComponents);
    }

    private void validateStatusShape() {
        if ((status == ClosureProcessStatus.SUCCESS) != (platformCommitCompanion != null)) {
            throw new IllegalArgumentException("platformCommitCompanion");
        }
        if ((status == ClosureProcessStatus.GAS_LIMIT_EXCEEDED) != (rejectedCharge != null)) {
            throw new IllegalArgumentException("rejectedCharge");
        }
        if (rejectedCharge == null) {
            if (rejectedWorkOccurrence != null) {
                throw new IllegalArgumentException("rejectedWorkOccurrence");
            }
            return;
        }
        boolean workOwned = rejectedCharge.owner() instanceof SharedGasMeter.WorkOwner;
        if (workOwned != (rejectedWorkOccurrence != null)) {
            throw new IllegalArgumentException("rejected owner");
        }
        if (workOwned) {
            String expected = ((SharedGasMeter.WorkOwner) rejectedCharge.owner())
                    .workOccurrenceIdentity();
            if (!expected.equals(rejectedWorkOccurrence.workIdentity())) {
                throw new IllegalArgumentException("rejected work identity");
            }
        }
    }

    private static <T> List<T> immutableList(List<T> values, String field) {
        ArrayList<T> copy = new ArrayList<T>(Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireDocumentOrder(List<ResultingDocument> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("resultingDocuments");
        }
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).documentId().compareTo(
                    values.get(index).documentId()) >= 0) {
                throw new IllegalArgumentException(
                        "resultingDocuments not in canonical order");
            }
        }
    }

    private static void requireOccurrenceOrder(
            List<ManagedOccurrenceBinding> values) {
        for (int index = 1; index < values.size(); index++) {
            ManagedOccurrenceBinding before = values.get(index - 1);
            ManagedOccurrenceBinding after = values.get(index);
            int occurrenceOrder = before.occurrenceIdentity().compareTo(
                    after.occurrenceIdentity());
            if (occurrenceOrder > 0
                    || (occurrenceOrder == 0
                    && before.bindingIdentity().compareTo(
                            after.bindingIdentity()) >= 0)) {
                throw new IllegalArgumentException(
                        "occurrenceBindings not in canonical order");
            }
        }
    }

    private static void requireGraphChangeOrdinals(List<GraphChange> values) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).graphChangeOrdinal() != index) {
                throw new IllegalArgumentException("graphChangeOrdinal");
            }
        }
    }

    private static void requireSubscriptionDeltaOrdinals(
            List<SubscriptionDelta> values) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).subscriptionDeltaOrdinal() != index) {
                throw new IllegalArgumentException("subscriptionDeltaOrdinal");
            }
        }
    }

    private static void requireCheckpointWriteOrdinals(
            List<CheckpointWrite> values) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).checkpointWriteOrdinal() != index) {
                throw new IllegalArgumentException("checkpointWriteOrdinal");
            }
        }
    }

    private static void requirePublicEventOrdinals(
            List<PublicEventOccurrence> values) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).publicEventOrdinal() != index) {
                throw new IllegalArgumentException("publicEventOrdinal");
            }
        }
    }

    private static void requireGasTrace(List<GasCharge> values, long totalGas) {
        long recomputedTotal = 0L;
        for (int index = 0; index < values.size(); index++) {
            GasCharge charge = values.get(index);
            if (charge.sequence() != index) {
                throw new IllegalArgumentException("gas sequence");
            }
            recomputedTotal = CanonicalOrders.requireSafeInteger(
                    Math.addExact(recomputedTotal, charge.subtotal()),
                    "recomputed totalGas");
        }
        if (recomputedTotal != totalGas) {
            throw new IllegalArgumentException("totalGas mismatch");
        }
    }

    private static void requireCompleteComponentMembership(
            List<ResultingDocument> documents,
            List<ResultingComponent> components) {
        if (components.isEmpty()) {
            throw new IllegalArgumentException("resultingComponents");
        }
        Map<String, ResultingComponent> byIdentity =
                new HashMap<String, ResultingComponent>();
        Set<DocumentId> memberDocuments = new HashSet<DocumentId>();
        for (ResultingComponent component : components) {
            if (byIdentity.put(component.componentIdentity(), component) != null) {
                throw new IllegalArgumentException("duplicate componentIdentity");
            }
            for (DocumentId member : component.orderedMemberDocumentIds()) {
                if (!memberDocuments.add(member)) {
                    throw new IllegalArgumentException(
                            "document belongs to multiple components");
                }
            }
        }
        Set<DocumentId> resultDocuments = new HashSet<DocumentId>();
        for (ResultingDocument document : documents) {
            resultDocuments.add(document.documentId());
            ResultingComponent component = byIdentity.get(
                    document.componentIdentity());
            if (component == null
                    || !component.componentStateIdentity().equals(
                            document.componentStateIdentity())
                    || !component.orderedMemberDocumentIds().contains(
                            document.documentId())) {
                throw new IllegalArgumentException(
                        "resulting document component mismatch");
            }
            int memberPosition = component.orderedMemberDocumentIds().indexOf(
                    document.documentId());
            if (!component.orderedMemberBlueIds().get(memberPosition).equals(
                    document.afterBlueId())) {
                throw new IllegalArgumentException(
                        "resulting document BlueId/component mismatch");
            }
        }
        if (!memberDocuments.equals(resultDocuments)) {
            throw new IllegalArgumentException("incomplete component membership");
        }
    }

    public ClosureProcessStatus status() { return status; }
    public String invocationIdentity() { return invocationIdentity; }
    public String inputClosureIdentity() { return inputClosureIdentity; }
    public String outputClosureIdentity() { return outputClosureIdentity; }
    public long graphGeneration() { return graphGeneration; }
    public List<ResultingDocument> resultingDocuments() { return resultingDocuments; }
    public List<ResultingComponent> resultingComponents() { return resultingComponents; }
    public List<ManagedOccurrenceBinding> occurrenceBindings() { return occurrenceBindings; }
    public String occurrenceBindingSetIdentity() { return occurrenceBindingSetIdentity; }
    public List<GraphChange> graphChanges() { return graphChanges; }
    public String graphChangesIdentity() { return graphChangesIdentity; }
    public List<SubscriptionDelta> subscriptionDeltas() { return subscriptionDeltas; }
    public String subscriptionDeltasIdentity() { return subscriptionDeltasIdentity; }
    public List<CheckpointWrite> checkpointWrites() { return checkpointWrites; }
    public String checkpointWritesIdentity() { return checkpointWritesIdentity; }
    public List<PublicEventOccurrence> publicEvents() { return publicEvents; }
    public String publicEventsIdentity() { return publicEventsIdentity; }
    public long totalGas() { return totalGas; }
    public List<GasCharge> gasTrace() { return gasTrace; }
    public String gasTraceIdentity() { return gasTraceIdentity; }
    public SharedGasMeter.RejectedCharge rejectedCharge() { return rejectedCharge; }
    public WorkOccurrence rejectedWorkOccurrence() { return rejectedWorkOccurrence; }
    public ClosureCommitPlan platformCommitCompanion() { return platformCommitCompanion; }
    public String diagnostic() { return diagnostic; }

    /** Complete processing result or a resource suspension before deterministic execution. */
    public abstract static class Attempt {
        private Attempt() { }
    }

    public static final class Complete extends Attempt {
        private final ClosureProcessResult result;

        public Complete(ClosureProcessResult result) {
            this.result = Objects.requireNonNull(result, "result");
        }

        public ClosureProcessResult result() { return result; }
    }

    public static final class NeedsResources extends Attempt {
        private final List<String> requiredBlueIds;

        public NeedsResources(List<String> requiredBlueIds) {
            ArrayList<String> copy = new ArrayList<String>(
                    Objects.requireNonNull(requiredBlueIds, "requiredBlueIds"));
            if (copy.isEmpty()) {
                throw new IllegalArgumentException("requiredBlueIds");
            }
            for (String value : copy) {
                Objects.requireNonNull(value, "requiredBlueIds item");
            }
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                    throw new IllegalArgumentException("requiredBlueIds not canonical");
                }
            }
            this.requiredBlueIds = Collections.unmodifiableList(copy);
        }

        public List<String> requiredBlueIds() { return requiredBlueIds; }
    }

    public static final class GraphChange {
        private final long graphChangeOrdinal;
        private final String changeKind;
        private final DocumentId sourceDocumentId;
        private final String sourcePath;
        private final Long beforeActivationGeneration;
        private final String beforeOccurrenceIdentity;
        private final String beforeBindingIdentity;
        private final DocumentId beforeTargetDocumentId;
        private final String beforeTargetBlueId;
        private final Long afterActivationGeneration;
        private final String afterOccurrenceIdentity;
        private final String afterBindingIdentity;
        private final DocumentId afterTargetDocumentId;
        private final String afterTargetBlueId;

        public GraphChange(
                long graphChangeOrdinal,
                String changeKind,
                DocumentId sourceDocumentId,
                String sourcePath,
                Long beforeActivationGeneration,
                String beforeOccurrenceIdentity,
                String beforeBindingIdentity,
                DocumentId beforeTargetDocumentId,
                String beforeTargetBlueId,
                Long afterActivationGeneration,
                String afterOccurrenceIdentity,
                String afterBindingIdentity,
                DocumentId afterTargetDocumentId,
                String afterTargetBlueId) {
            this.graphChangeOrdinal = CanonicalOrders.requireSafeInteger(
                    graphChangeOrdinal, "graphChangeOrdinal");
            this.changeKind = requireOneOf(
                    changeKind, "changeKind", "ADD", "REMOVE", "RETARGET", "REBIND");
            this.sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            this.beforeActivationGeneration = safeNullable(
                    beforeActivationGeneration, "beforeActivationGeneration");
            this.beforeOccurrenceIdentity = beforeOccurrenceIdentity;
            this.beforeBindingIdentity = beforeBindingIdentity;
            this.beforeTargetDocumentId = beforeTargetDocumentId;
            this.beforeTargetBlueId = beforeTargetBlueId;
            this.afterActivationGeneration = safeNullable(
                    afterActivationGeneration, "afterActivationGeneration");
            this.afterOccurrenceIdentity = afterOccurrenceIdentity;
            this.afterBindingIdentity = afterBindingIdentity;
            this.afterTargetDocumentId = afterTargetDocumentId;
            this.afterTargetBlueId = afterTargetBlueId;
            boolean beforeAbsent = this.beforeActivationGeneration == null
                    && beforeOccurrenceIdentity == null
                    && beforeBindingIdentity == null
                    && beforeTargetDocumentId == null
                    && beforeTargetBlueId == null;
            boolean beforeComplete = this.beforeActivationGeneration != null
                    && beforeOccurrenceIdentity != null
                    && beforeBindingIdentity != null
                    && beforeTargetDocumentId != null
                    && beforeTargetBlueId != null;
            boolean afterAbsent = this.afterActivationGeneration == null
                    && afterOccurrenceIdentity == null
                    && afterBindingIdentity == null
                    && afterTargetDocumentId == null
                    && afterTargetBlueId == null;
            boolean afterComplete = this.afterActivationGeneration != null
                    && afterOccurrenceIdentity != null
                    && afterBindingIdentity != null
                    && afterTargetDocumentId != null
                    && afterTargetBlueId != null;
            if (!(beforeAbsent || beforeComplete) || !(afterAbsent || afterComplete)) {
                throw new IllegalArgumentException("graph change side");
            }
            if (("ADD".equals(this.changeKind)
                    && !(beforeAbsent && afterComplete))
                    || ("REMOVE".equals(this.changeKind)
                    && !(beforeComplete && afterAbsent))
                    || (("RETARGET".equals(this.changeKind)
                    || "REBIND".equals(this.changeKind))
                    && !(beforeComplete && afterComplete))) {
                throw new IllegalArgumentException("graph change kind/side mismatch");
            }
            if ("REBIND".equals(this.changeKind)
                    && (!beforeOccurrenceIdentity.equals(afterOccurrenceIdentity)
                    || !beforeTargetDocumentId.equals(afterTargetDocumentId)
                    || !beforeActivationGeneration.equals(afterActivationGeneration))) {
                throw new IllegalArgumentException("REBIND must preserve lineage");
            }
            if ("REBIND".equals(this.changeKind)
                    && beforeBindingIdentity.equals(afterBindingIdentity)
                    && beforeTargetBlueId.equals(afterTargetBlueId)) {
                throw new IllegalArgumentException("REBIND cannot be a no-op");
            }
            if ("RETARGET".equals(this.changeKind)
                    && beforeOccurrenceIdentity.equals(afterOccurrenceIdentity)
                    && beforeTargetDocumentId.equals(afterTargetDocumentId)) {
                throw new IllegalArgumentException("RETARGET must change lineage");
            }
        }

        public long graphChangeOrdinal() { return graphChangeOrdinal; }
        public String changeKind() { return changeKind; }
        public DocumentId sourceDocumentId() { return sourceDocumentId; }
        public String sourcePath() { return sourcePath; }
        public Long beforeActivationGeneration() { return beforeActivationGeneration; }
        public String beforeOccurrenceIdentity() { return beforeOccurrenceIdentity; }
        public String beforeBindingIdentity() { return beforeBindingIdentity; }
        public DocumentId beforeTargetDocumentId() { return beforeTargetDocumentId; }
        public String beforeTargetBlueId() { return beforeTargetBlueId; }
        public Long afterActivationGeneration() { return afterActivationGeneration; }
        public String afterOccurrenceIdentity() { return afterOccurrenceIdentity; }
        public String afterBindingIdentity() { return afterBindingIdentity; }
        public DocumentId afterTargetDocumentId() { return afterTargetDocumentId; }
        public String afterTargetBlueId() { return afterTargetBlueId; }
    }

    public static final class CheckpointWrite {
        private final long checkpointWriteOrdinal;
        private final String targetManagedScopeIdentity;
        private final String rawChannelKey;
        private final boolean beforePresent;
        private final String beforeDomainBlueId;
        private final CheckpointDomain beforeDomainValue;
        private final String beforeSubjectBlueId;
        private final boolean afterPresent;
        private final String afterDomainBlueId;
        private final CheckpointDomain afterDomainValue;
        private final String afterSubjectBlueId;

        public CheckpointWrite(
                long checkpointWriteOrdinal,
                String targetManagedScopeIdentity,
                String rawChannelKey,
                boolean beforePresent,
                String beforeDomainBlueId,
                CheckpointDomain beforeDomainValue,
                String beforeSubjectBlueId,
                boolean afterPresent,
                String afterDomainBlueId,
                CheckpointDomain afterDomainValue,
                String afterSubjectBlueId,
                CheckpointDomain.DirectBlueIdCalculator directBlueIdCalculator) {
            this.checkpointWriteOrdinal = CanonicalOrders.requireSafeInteger(
                    checkpointWriteOrdinal, "checkpointWriteOrdinal");
            this.targetManagedScopeIdentity = Objects.requireNonNull(
                    targetManagedScopeIdentity, "targetManagedScopeIdentity");
            this.rawChannelKey = Objects.requireNonNull(rawChannelKey, "rawChannelKey");
            this.beforePresent = beforePresent;
            this.beforeDomainBlueId = beforeDomainBlueId;
            this.beforeDomainValue = beforeDomainValue;
            this.beforeSubjectBlueId = beforeSubjectBlueId;
            this.afterPresent = afterPresent;
            this.afterDomainBlueId = afterDomainBlueId;
            this.afterDomainValue = afterDomainValue;
            this.afterSubjectBlueId = afterSubjectBlueId;
            if (beforePresent != (beforeDomainBlueId != null
                    && beforeDomainValue != null && beforeSubjectBlueId != null)
                    || afterPresent != (afterDomainBlueId != null
                    && afterDomainValue != null && afterSubjectBlueId != null)) {
                throw new IllegalArgumentException("checkpoint presence");
            }
            if (!beforePresent && !afterPresent) {
                throw new IllegalArgumentException("checkpoint write cannot be a no-op");
            }
            CheckpointDomain.DirectBlueIdCalculator calculator =
                    Objects.requireNonNull(
                            directBlueIdCalculator, "directBlueIdCalculator");
            if (beforePresent && !beforeDomainBlueId.equals(
                    beforeDomainValue.checkpointDomainBlueId(calculator))) {
                throw new IllegalArgumentException("beforeDomainBlueId");
            }
            if (afterPresent && !afterDomainBlueId.equals(
                    afterDomainValue.checkpointDomainBlueId(calculator))) {
                throw new IllegalArgumentException("afterDomainBlueId");
            }
            if (beforePresent
                    && afterPresent
                    && beforeDomainBlueId.equals(afterDomainBlueId)
                    && beforeSubjectBlueId.equals(afterSubjectBlueId)
                    && beforeDomainValue.exactValue().equals(
                            afterDomainValue.exactValue())) {
                throw new IllegalArgumentException(
                        "checkpoint write cannot retain identical state");
            }
        }

        public long checkpointWriteOrdinal() { return checkpointWriteOrdinal; }
        public String targetManagedScopeIdentity() { return targetManagedScopeIdentity; }
        public String rawChannelKey() { return rawChannelKey; }
        public boolean beforePresent() { return beforePresent; }
        public String beforeDomainBlueId() { return beforeDomainBlueId; }
        public Map<String, Object> beforeDomainValue() {
            return beforeDomainValue == null ? null : beforeDomainValue.exactValue();
        }
        public String beforeSubjectBlueId() { return beforeSubjectBlueId; }
        public boolean afterPresent() { return afterPresent; }
        public String afterDomainBlueId() { return afterDomainBlueId; }
        public Map<String, Object> afterDomainValue() {
            return afterDomainValue == null ? null : afterDomainValue.exactValue();
        }
        public String afterSubjectBlueId() { return afterSubjectBlueId; }
    }

    public static final class ChannelOccurrence {
        private final String channelOccurrenceIdentity;
        private final DocumentId managedDocumentId;
        private final String scopePath;
        private final long scopeActivationGeneration;
        private final String rawChannelKey;
        private final String effectiveRuntimeContributionBlueId;
        private final String subscriptionHeaderBlueId;

        public ChannelOccurrence(
                String channelOccurrenceIdentity,
                DocumentId managedDocumentId,
                String scopePath,
                long scopeActivationGeneration,
                String rawChannelKey,
                String effectiveRuntimeContributionBlueId,
                String subscriptionHeaderBlueId) {
            this.channelOccurrenceIdentity = Objects.requireNonNull(
                    channelOccurrenceIdentity, "channelOccurrenceIdentity");
            this.managedDocumentId = Objects.requireNonNull(
                    managedDocumentId, "managedDocumentId");
            this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
            this.scopeActivationGeneration = CanonicalOrders.requireSafeInteger(
                    scopeActivationGeneration, "scopeActivationGeneration");
            this.rawChannelKey = Objects.requireNonNull(rawChannelKey, "rawChannelKey");
            this.effectiveRuntimeContributionBlueId = Objects.requireNonNull(
                    effectiveRuntimeContributionBlueId,
                    "effectiveRuntimeContributionBlueId");
            this.subscriptionHeaderBlueId = Objects.requireNonNull(
                    subscriptionHeaderBlueId, "subscriptionHeaderBlueId");
        }

        public String channelOccurrenceIdentity() { return channelOccurrenceIdentity; }
        public DocumentId managedDocumentId() { return managedDocumentId; }
        public String scopePath() { return scopePath; }
        public long scopeActivationGeneration() { return scopeActivationGeneration; }
        public String rawChannelKey() { return rawChannelKey; }
        public String effectiveRuntimeContributionBlueId() {
            return effectiveRuntimeContributionBlueId;
        }
        public String subscriptionHeaderBlueId() { return subscriptionHeaderBlueId; }
    }

    public static final class SubscriptionState {
        private final String subscriptionIdentity;
        private final ChannelOccurrence channelOccurrence;
        private final String documentBlueId;
        private final long graphGeneration;
        private final long componentGeneration;

        public SubscriptionState(
                String subscriptionIdentity,
                ChannelOccurrence channelOccurrence,
                String documentBlueId,
                long graphGeneration,
                long componentGeneration) {
            this.subscriptionIdentity = Objects.requireNonNull(
                    subscriptionIdentity, "subscriptionIdentity");
            this.channelOccurrence = Objects.requireNonNull(
                    channelOccurrence, "channelOccurrence");
            this.documentBlueId = Objects.requireNonNull(
                    documentBlueId, "documentBlueId");
            this.graphGeneration = CanonicalOrders.requireSafeInteger(
                    graphGeneration, "graphGeneration");
            this.componentGeneration = CanonicalOrders.requireSafeInteger(
                    componentGeneration, "componentGeneration");
        }

        public String subscriptionIdentity() { return subscriptionIdentity; }
        public ChannelOccurrence channelOccurrence() { return channelOccurrence; }
        public String documentBlueId() { return documentBlueId; }
        public long graphGeneration() { return graphGeneration; }
        public long componentGeneration() { return componentGeneration; }
    }

    public static final class SubscriptionDelta {
        private final long subscriptionDeltaOrdinal;
        private final String operation;
        private final String targetManagedScopeIdentity;
        private final String channelOccurrenceIdentity;
        private final SubscriptionState beforeSubscription;
        private final SubscriptionState afterSubscription;

        public SubscriptionDelta(
                long subscriptionDeltaOrdinal,
                String operation,
                String targetManagedScopeIdentity,
                String channelOccurrenceIdentity,
                SubscriptionState beforeSubscription,
                SubscriptionState afterSubscription) {
            this.subscriptionDeltaOrdinal = CanonicalOrders.requireSafeInteger(
                    subscriptionDeltaOrdinal, "subscriptionDeltaOrdinal");
            this.operation = requireOneOf(
                    operation, "operation", "ADD", "REMOVE", "REPLACE");
            this.targetManagedScopeIdentity = Objects.requireNonNull(
                    targetManagedScopeIdentity, "targetManagedScopeIdentity");
            this.channelOccurrenceIdentity = Objects.requireNonNull(
                    channelOccurrenceIdentity, "channelOccurrenceIdentity");
            this.beforeSubscription = beforeSubscription;
            this.afterSubscription = afterSubscription;
            if (beforeSubscription == null && afterSubscription == null) {
                throw new IllegalArgumentException("subscription delta sides");
            }
            if (("ADD".equals(this.operation)
                    && !(beforeSubscription == null && afterSubscription != null))
                    || ("REMOVE".equals(this.operation)
                    && !(beforeSubscription != null && afterSubscription == null))
                    || ("REPLACE".equals(this.operation)
                    && !(beforeSubscription != null && afterSubscription != null))) {
                throw new IllegalArgumentException(
                        "subscription operation/side mismatch");
            }
            if (beforeSubscription != null
                    && !channelOccurrenceIdentity.equals(
                            beforeSubscription.channelOccurrence()
                                    .channelOccurrenceIdentity())) {
                throw new IllegalArgumentException(
                        "before channel occurrence identity");
            }
            if (afterSubscription != null
                    && !channelOccurrenceIdentity.equals(
                            afterSubscription.channelOccurrence()
                                    .channelOccurrenceIdentity())) {
                throw new IllegalArgumentException(
                        "after channel occurrence identity");
            }
            if ("REPLACE".equals(this.operation)
                    && beforeSubscription.subscriptionIdentity().equals(
                            afterSubscription.subscriptionIdentity())) {
                throw new IllegalArgumentException("REPLACE cannot be a no-op");
            }
        }

        public long subscriptionDeltaOrdinal() { return subscriptionDeltaOrdinal; }
        public String operation() { return operation; }
        public String targetManagedScopeIdentity() { return targetManagedScopeIdentity; }
        public String channelOccurrenceIdentity() { return channelOccurrenceIdentity; }
        public SubscriptionState beforeSubscription() { return beforeSubscription; }
        public SubscriptionState afterSubscription() { return afterSubscription; }

        public String beforeSubscriptionIdentity() {
            return beforeSubscription == null ? null : beforeSubscription.subscriptionIdentity();
        }

        public String afterSubscriptionIdentity() {
            return afterSubscription == null ? null : afterSubscription.subscriptionIdentity();
        }

        public String beforeDocumentBlueId() {
            return beforeSubscription == null ? null : beforeSubscription.documentBlueId();
        }

        public String afterDocumentBlueId() {
            return afterSubscription == null ? null : afterSubscription.documentBlueId();
        }

        public Long beforeGraphGeneration() {
            return beforeSubscription == null
                    ? null : Long.valueOf(beforeSubscription.graphGeneration());
        }

        public Long afterGraphGeneration() {
            return afterSubscription == null
                    ? null : Long.valueOf(afterSubscription.graphGeneration());
        }

        public Long beforeComponentGeneration() {
            return beforeSubscription == null
                    ? null : Long.valueOf(beforeSubscription.componentGeneration());
        }

        public Long afterComponentGeneration() {
            return afterSubscription == null
                    ? null : Long.valueOf(afterSubscription.componentGeneration());
        }
    }

    private static String requireOneOf(
            String value, String field, String first, String... remaining) {
        if (first.equals(value)) {
            return value;
        }
        for (String candidate : remaining) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException(field);
    }

    private static Long safeNullable(Long value, String field) {
        return value == null ? null : Long.valueOf(
                CanonicalOrders.requireSafeInteger(value.longValue(), field));
    }
}
