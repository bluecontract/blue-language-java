package blue.language.processor.closure;

import blue.language.processor.GasChargeContext;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.FrozenNode;

import java.util.*;

/**
 * One independently atomic, completed operation from the canonical same-origin driver.
 * Other operations returned by that driver are not implicitly in this transaction. Only the
 * listed owned lineages may be mutated; dependencies name separately settled source operations.
 * Construction is internal to the real interpreter's quiescent result assembler.
 */
public final class SameOriginOperationResult {
    private final ClosureInvocationInput origin;
    private final String operationIdentity;
    private final Map<DocumentId, String> originalSeeds, consumedSources;
    private final List<ObservedSource> observedSources;
    private final List<Admission> admissions;
    private final ProcessorStatus status;
    private final List<ManagedDocumentSnapshot> predecessors;
    private final List<ResultingDocument> documents;
    private final List<ComponentSnapshot> components;
    private final List<ManagedOccurrenceBinding> bindings;
    private final List<GraphChange> graphChanges;
    private final List<SubscriptionDelta> subscriptions;
    private final List<CheckpointWrite> checkpoints;
    private final List<Event> events;
    private final List<GasTraceEntry> gasTrace;
    private final String gasTraceIdentity;
    private final long totalGas;
    private final SourceObservationProgram sourceProgram;
    private final Failure failure;
    private final SameOriginGroupEvidence groupEvidence;

    SameOriginOperationResult(ClosureInvocationInput origin, String operationIdentity, Map<DocumentId, String> originalSeeds,
            List<Admission> admissions, Map<DocumentId, String> consumedSources, List<ObservedSource> observedSources, ProcessorStatus status,
            List<ManagedDocumentSnapshot> predecessors, List<ResultingDocument> documents,
            List<ComponentSnapshot> components, List<ManagedOccurrenceBinding> bindings,
            List<GraphChange> graphChanges, List<SubscriptionDelta> subscriptions, List<CheckpointWrite> checkpoints,
            List<Event> events, List<GasTraceEntry> gasTrace, SourceObservationProgram sourceProgram, Failure failure) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.operationIdentity = ClosureValueSupport.requireSha256Identity(operationIdentity, "operationIdentity");
        this.originalSeeds = immutableMap(originalSeeds); this.consumedSources = immutableMap(consumedSources);
        ArrayList<ObservedSource> observed = new ArrayList<>(Objects.requireNonNull(observedSources, "observedSources"));
        observed.sort(Comparator.comparing(ObservedSource::consumerDocumentId).thenComparing(ObservedSource::documentId));
        ObservedSource previous = null;
        for (ObservedSource value : observed) {
            if (!this.originalSeeds.containsKey(value.consumerDocumentId()) || !this.consumedSources.containsKey(value.documentId())
                    || value.consumerDocumentId().equals(value.documentId()))
                throw new IllegalArgumentException("Observed source must belong to an owned actual consumer and consumed external source");
            if (previous != null && previous.consumerDocumentId().equals(value.consumerDocumentId()) && previous.documentId().equals(value.documentId()))
                throw new IllegalArgumentException("Duplicate consumer/source observation");
            previous = value;
        }
        this.observedSources = Collections.unmodifiableList(observed);
        this.admissions = immutable(admissions); this.status = Objects.requireNonNull(status);
        this.predecessors = immutable(predecessors); this.documents = immutable(documents);
        this.components = immutable(components); this.bindings = immutable(bindings);
        this.graphChanges = immutable(graphChanges); this.subscriptions = immutable(subscriptions);
        this.checkpoints = immutable(checkpoints); this.events = immutable(events); this.gasTrace = immutable(gasTrace);
        this.totalGas = ClosureResultAssemblySupport.totalGas(gasTrace);
        this.gasTraceIdentity = ClosureResultAssemblySupport.sequenceIdentity(ClosureIdentityService.Constructor.GAS_TRACE, gasTrace);
        this.sourceProgram = sourceProgram; this.failure = failure;
        if (status == ProcessorStatus.SUCCESS) {
            if (sourceProgram == null || failure != null || !operationIdentity.equals(sourceProgram.invocationIdentity()))
                throw new IllegalArgumentException("Successful group requires its exact source program and no failure");
        } else if ((status != ProcessorStatus.GAS_LIMIT_EXCEEDED && status != ProcessorStatus.RUNTIME_FATAL)
                || failure == null || sourceProgram != null || !events.isEmpty() || !graphChanges.isEmpty()
                || !subscriptions.isEmpty() || !checkpoints.isEmpty()) {
            throw new IllegalArgumentException("Failed group requires a recognized consuming failure and no tentative effects");
        }
        Set<DocumentId> actual = new TreeSet<>();
        for (ResultingDocument document : documents) if (!actual.add(document.documentId()))
            throw new IllegalArgumentException("Duplicate owned group lineage");
        if (!actual.equals(originalSeeds.keySet()) || actual.isEmpty()
                || !Collections.disjoint(actual, consumedSources.keySet()))
            throw new IllegalArgumentException("Group ownership and independent dependencies disagree");
        this.groupEvidence = SameOriginGroupEvidence.fromOperation(this);
    }

    /** Exact immutable driver input, retained by reference; not a claim that all its lineages commit together. */
    public ClosureInvocationInput origin() { return origin; }
    public String operationIdentity() { return operationIdentity; }
    public SameOriginGroupEvidence groupEvidence() { return groupEvidence; }
    public Set<DocumentId> ownedDocumentIds() { return originalSeeds.keySet(); }
    public Map<DocumentId, String> originalSeedByMember() { return originalSeeds; }
    public List<Admission> admissions() { return admissions; }
    public Map<DocumentId, String> consumedSourceOperations() { return consumedSources; }
    /** Successful observation pins before this attempt, from producing evidence/gaps, never ambient host heads. */
    public List<ObservedSource> observedSources() { return observedSources; }
    public Optional<ObservedSource> observedSource(DocumentId consumer, DocumentId source) {
        return observedSources.stream().filter(value -> value.consumerDocumentId().equals(consumer) && value.documentId().equals(source)).findFirst();
    }
    public ProcessorStatus status() { return status; }
    public List<ManagedDocumentSnapshot> predecessors() { return predecessors; }
    public List<ResultingDocument> resultingDocuments() { return documents; }
    public List<ComponentSnapshot> resultingComponents() { return components; }
    public List<ManagedOccurrenceBinding> occurrenceBindings() { return bindings; }
    public List<GraphChange> graphChanges() { return graphChanges; }
    public List<SubscriptionDelta> subscriptionDeltas() { return subscriptions; }
    public List<CheckpointWrite> checkpointWrites() { return checkpoints; }
    public List<Event> events() { return events; }
    public List<GasTraceEntry> gasTrace() { return gasTrace; }
    public String gasTraceIdentity() { return gasTraceIdentity; }
    public long totalGas() { return totalGas; }
    public Optional<SourceObservationProgram> sourceProgram() { return Optional.ofNullable(sourceProgram); }
    public Optional<Failure> failure() { return Optional.ofNullable(failure); }

    /** A bodyless exact observed boundary; only the owning result assembler may mint it. */
    public static final class ObservedSource {
        private final DocumentId documentId;
        private final DocumentId consumerDocumentId;
        private final String blueId;
        private final long epoch;
        ObservedSource(DocumentId consumerDocumentId, DocumentId documentId, String blueId, long epoch) {
            this.consumerDocumentId = Objects.requireNonNull(consumerDocumentId, "consumerDocumentId");
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.blueId = ClosureValueSupport.requireBlueId(blueId, "observed source BlueId");
            this.epoch = ClosureValueSupport.requireSafeInteger(epoch, "observed source epoch");
        }
        public DocumentId documentId() { return documentId; }
        public DocumentId consumerDocumentId() { return consumerDocumentId; }
        public String blueId() { return blueId; }
        public long epoch() { return epoch; }
    }

    /** Accepted semantic admission, never a physical attempt number. */
    public static final class Admission {
        private final String site;
        private final Set<DocumentId> members;
        Admission(String site, Collection<DocumentId> members) {
            this.site = ClosureValueSupport.requireSha256Identity(site, "admissionSite");
            this.members = Collections.unmodifiableSet(new TreeSet<>(members));
        }
        public String canonicalSite() { return site; }
        public Set<DocumentId> members() { return members; }
    }

    /** Intrinsic emission; hosting/publication roles do not change its occurrence identity. */
    public static final class Event {
        private final long ordinal;
        private final DocumentId source;
        private final String occurrenceIdentity, blueId;
        private final FrozenNode value;
        Event(long ordinal, DocumentId source, String occurrenceIdentity, String blueId, FrozenNode value) {
            this.ordinal = ClosureValueSupport.requireSafeInteger(ordinal, "eventOrdinal");
            this.source = Objects.requireNonNull(source);
            this.occurrenceIdentity = ClosureValueSupport.requireSha256Identity(occurrenceIdentity, "eventOccurrence");
            this.blueId = ClosureValueSupport.requireBlueId(blueId, "eventBlueId"); this.value = Objects.requireNonNull(value);
        }
        public long ordinal() { return ordinal; }
        public DocumentId sourceDocumentId() { return source; }
        public String occurrenceIdentity() { return occurrenceIdentity; }
        public String eventBlueId() { return blueId; }
        public FrozenNode event() { return value; }
    }

    /** Immutable ordinary-charge diagnostic; never exposes a mutable exception/host stack trace. */
    public static final class ChargeRejection {
        private final String namespace, counter, localDocument;
        private final long quantity, weight, admitted, limit, remaining;
        private final GasLimitExceededException.ApplicableCapKind cap;
        private final GasChargeContext context;
        ChargeRejection(GasLimitExceededException rejected) {
            namespace = rejected.namespace(); counter = rejected.counter(); quantity = rejected.quantity();
            weight = rejected.weight(); admitted = rejected.admittedGas(); limit = rejected.gasLimit();
            remaining = rejected.remainingBeforeCharge(); cap = rejected.applicableCapKind();
            localDocument = rejected.localDocumentId(); context = rejected.chargeContext();
        }
        public String namespace() { return namespace; }
        public String counter() { return counter; }
        public long quantity() { return quantity; }
        public long weight() { return weight; }
        public long admittedGas() { return admitted; }
        public long gasLimit() { return limit; }
        public long remainingBeforeCharge() { return remaining; }
        public GasLimitExceededException.ApplicableCapKind applicableCapKind() { return cap; }
        public String localDocumentId() { return localDocument; }
        public GasChargeContext chargeContext() { return context; }
    }

    /** Failure keeps rejected charge and rejected union separate; neither is a successful event. */
    public static final class Failure {
        private final ProcessorDiagnostic diagnostic;
        private final String site;
        private final ChargeRejection charge;
        private final JoinRejection join;
        Failure(ProcessorDiagnostic diagnostic, String site, ChargeRejection charge, JoinRejection join) {
            this.diagnostic = Objects.requireNonNull(diagnostic);
            this.site = ClosureValueSupport.requireSha256Identity(site, "failureSite");
            if (charge != null && join != null) throw new IllegalArgumentException("A rejected union is not an ordinary charge");
            this.charge = charge; this.join = join;
        }
        public ProcessorDiagnostic diagnostic() { return diagnostic; }
        public String canonicalSite() { return site; }
        public Optional<ChargeRejection> rejectedCharge() { return Optional.ofNullable(charge); }
        public Optional<JoinRejection> rejectedAdmission() { return Optional.ofNullable(join); }
    }

    /** Exact proposed participant budgets, addressed by canonical members rather than input indices. */
    public static final class JoinRejection {
        private final long limit, localLimit;
        private final String localDocument;
        private final List<Contribution> contributions;
        JoinRejection(long limit, String localDocument, long localLimit, List<Contribution> contributions) {
            this.limit = limit; this.localDocument = localDocument; this.localLimit = localLimit;
            this.contributions = immutable(contributions);
        }
        public long limit() { return limit; }
        public String localDocumentId() { return localDocument; }
        public long localLimit() { return localLimit; }
        public List<Contribution> contributions() { return contributions; }
    }

    public static final class Contribution {
        private final Set<DocumentId> members;
        private final long admitted, reserved;
        private final Map<String, Long> admittedLocal, reservedLocal;
        Contribution(Collection<DocumentId> members, long admitted, long reserved,
                Map<String, Long> admittedLocal, Map<String, Long> reservedLocal) {
            this.members = Collections.unmodifiableSet(new TreeSet<>(members));
            this.admitted = admitted; this.reserved = reserved;
            this.admittedLocal = immutableMap(admittedLocal); this.reservedLocal = immutableMap(reservedLocal);
        }
        public Set<DocumentId> members() { return members; }
        public long admitted() { return admitted; }
        public long reserved() { return reserved; }
        public Map<String, Long> admittedLocal() { return admittedLocal; }
        public Map<String, Long> reservedLocal() { return reservedLocal; }
    }

    private static <T> List<T> immutable(List<T> values) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(values));
        for (T value : copy) Objects.requireNonNull(value); return Collections.unmodifiableList(copy);
    }
    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(values)));
    }
}
