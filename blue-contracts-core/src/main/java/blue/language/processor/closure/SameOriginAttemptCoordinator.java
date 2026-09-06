package blue.language.processor.closure;

import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasMeter;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ManagedDocumentStepRuntime;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Owns the lifetimes of tentative same-origin scopes and their live runtimes.
 *
 * <p>This is an execution primitive, not a committed-result constructor. The
 * session must journal document/queue/output mutations against the returned
 * attempt object, restore every invalidated attempt's authoritative predecessor,
 * and run its still-due own seeds with {@link #attempt(DocumentId)}'s fresh
 * runtime. In particular, a failed candidate is not a published failure while
 * it depends on an unfinished independent producer.</p>
 */
final class SameOriginAttemptCoordinator implements AutoCloseable {
    enum State { RUNNING, SUCCESS_CANDIDATE, FAILURE_CANDIDATE, INVALIDATED, SETTLED }

    private final DocumentProcessor owner;
    private final ExecutionPolicy policy;
    private final ManagedDocumentStepContinuation continuation;
    private final Map<DocumentId, List<DocumentId>> initialComponents = new TreeMap<DocumentId, List<DocumentId>>();
    private final Map<DocumentId, Attempt> current = new TreeMap<DocumentId, Attempt>();
    private final List<Attempt> allAttempts = new ArrayList<Attempt>();
    private final List<Invalidation> invalidations = new ArrayList<Invalidation>();
    private final Map<GasMeter.MultiGroupJoinResult, List<AdmissionContribution>> admissionContributions = new IdentityHashMap<>();
    private long nextAttemptOrdinal;
    private long nextAdmissionOrder;
    private long nextAdmittedChargeOrder;
    private boolean closed;

    SameOriginAttemptCoordinator(DocumentProcessor owner, ExecutionPolicy policy,
            ManagedDocumentStepContinuation continuation, List<ComponentSnapshot> components) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.continuation = Objects.requireNonNull(continuation, "continuation");
        for (ComponentSnapshot component : Objects.requireNonNull(components, "components")) {
            List<DocumentId> members = Collections.unmodifiableList(new ArrayList<DocumentId>(component.orderedMemberDocumentIds()));
            for (DocumentId member : members) {
                if (initialComponents.put(member, members) != null) {
                    throw new IllegalArgumentException("Initial atomic components overlap");
                }
            }
        }
    }

    /** A new attempt always has a new runtime/memo; an abandoned group is never split or refunded. */
    Attempt attempt(DocumentId document) {
        ensureOpen();
        Attempt selected = current.get(Objects.requireNonNull(document, "document"));
        if (selected != null) return selected.root();
        List<DocumentId> initial = initialComponents.get(document);
        if (initial == null) throw new IllegalArgumentException("Attempt owner is outside the admitted component cut");
        LinkedHashMap<String, Long> local = new LinkedHashMap<String, Long>();
        for (Map.Entry<DocumentId, Long> entry : policy.localLimits().entrySet()) local.put(entry.getKey().value(), entry.getValue());
        ManagedDocumentStepRuntime runtime = new ManagedDocumentStepRuntime(owner, policy.sharedLimit(), local, continuation);
        Attempt created = new Attempt(nextAttemptOrdinal++, initial, runtime);
        runtime.observeAdmittedGas((originalRuntime, entry) -> created.originalCharges.add(
                new AdmittedCharge(nextAdmittedChargeOrder++, entry)));
        allAttempts.add(created);
        for (DocumentId member : initial) {
            if (current.put(member, created) != null) throw new IllegalStateException("Reconstruction split a still-valid initial component");
        }
        return created;
    }

    ManagedDocumentStepRuntime runtime(DocumentId document) {
        Attempt selected = attempt(document);
        selected.requireMutable();
        return selected.runtimeByDocument.get(document);
    }

    boolean failedCandidate(DocumentId document) {
        Attempt selected = current.get(document);
        return selected != null && selected.root().state == State.FAILURE_CANDIDATE;
    }

    /** A terminal failed source is consumed as a disposition, never offered as tentative state. */
    void consumesFailedDisposition(DocumentId consumer, DocumentId producer, String canonicalSite) {
        Attempt source = current.get(producer);
        if (source == null || source.root().state != State.FAILURE_CANDIDATE)
            throw new IllegalStateException("No terminal failed source disposition at this canonical site");
        Attempt dependent = attempt(consumer); dependent.requireMutable();
        if (dependent == source.root()) throw new IllegalArgumentException("An atomic group cannot consume its own failure");
        ClosureValueSupport.requireSha256Identity(canonicalSite, "canonicalSite");
        dependent.observedSources.put(producer, source.root());
    }

    /** First touch is a dependency, not a budget union or ownership admission. */
    void observes(DocumentId consumer, DocumentId producer, String canonicalSite) {
        Attempt dependent = attempt(consumer);
        Attempt source = attempt(producer);
        dependent.requireMutable();
        if (dependent == source) return;
        if (source.state == State.FAILURE_CANDIDATE) {
            throw new IllegalStateException("A failed producer cannot offer tentative state");
        }
        if (source.state == State.SETTLED) return;
        dependent.dependencies.putIfAbsent(source, ClosureValueSupport.requireSha256Identity(canonicalSite, "canonicalSite"));
        dependent.observedSources.put(producer, source);
    }

    /**
     * Admits a proposed feedback component at its canonical site. The owning
     * session charges the existing topology/join work before this call. The
     * whole proposed union is preflighted, including live child reservations;
     * no successful pairwise prefix may leak from a rejected multi-way join.
     */
    GasMeter.MultiGroupJoinResult admitFeedback(DocumentId initiatingDocument,
            Collection<DocumentId> proposedMembers, String canonicalSite) {
        Attempt initiating = attempt(initiatingDocument);
        initiating.requireMutable();
        String site = ClosureValueSupport.requireSha256Identity(canonicalSite, "canonicalSite");
        TreeMap<DocumentId, Attempt> selected = new TreeMap<DocumentId, Attempt>();
        selected.put(initiating.firstDocument(), initiating);
        for (DocumentId document : Objects.requireNonNull(proposedMembers, "proposedMembers")) {
            Attempt value = attempt(document);
            if (value.state == State.INVALIDATED || value.state == State.SETTLED) {
                throw new IllegalStateException("Cannot admit invalidated or published work into a scope");
            }
            selected.put(value.firstDocument(), value);
        }
        List<ManagedDocumentStepRuntime> meters = new ArrayList<ManagedDocumentStepRuntime>();
        List<Set<DocumentId>> participantMembers = new ArrayList<>(); participantMembers.add(initiating.members());
        for (Attempt value : selected.values()) { meters.add(value.firstRuntime()); participantMembers.add(value.members()); }
        GasMeter.MultiGroupJoinResult result = initiating.firstRuntime().tryJoinGasGroups(meters);
        List<AdmissionContribution> contributions = new ArrayList<>();
        for (GasMeter.GroupContribution contribution : result.contributions())
            contributions.add(new AdmissionContribution(participantMembers.get(contribution.firstInputIndex()), contribution));
        admissionContributions.put(result, Collections.unmodifiableList(contributions));
        if (!result.joined() || result.alreadyJoined()) return result;
        for (Attempt value : selected.values()) {
            if (value == initiating) continue;
            initiating.members.addAll(value.members);
            initiating.runtimeByDocument.putAll(value.runtimeByDocument);
            initiating.originalAttempts.addAll(value.originalAttempts);
            initiating.dependencies.putAll(value.dependencies);
            initiating.observedSources.putAll(value.observedSources);
            initiating.admissions.addAll(value.admissions);
            if (value.failure != null && initiating.failure == null) initiating.failure = value.failure;
            value.parent = initiating;
            for (DocumentId member : value.members) current.put(member, initiating);
        }
        initiating.dependencies.entrySet().removeIf(entry -> entry.getKey().root() == initiating);
        initiating.observedSources.entrySet().removeIf(entry -> entry.getValue().root() == initiating);
        initiating.admissions.add(new Admission(nextAdmissionOrder++, site, initiating.members, result));
        initiating.admissions.sort((left, right) -> Long.compare(left.executionOrder, right.executionOrder));
        if (initiating.failure != null) initiating.state = State.FAILURE_CANDIDATE;
        return result;
    }

    void successful(DocumentId ownerDocument) {
        Attempt value = attempt(ownerDocument);
        value.requireMutable();
        value.state = State.SUCCESS_CANDIDATE;
    }

    /**
     * Freezes the canonical failed prefix before any cleanup and invalidates
     * the complete reverse dependency closure, including failed candidates.
     * The returned invalidation owns every admitted third-party member.
     */
    List<Invalidation> failed(DocumentId ownerDocument, ProcessorStatus status,
            ProcessorDiagnostic diagnostic, String canonicalSite) {
        return failed(ownerDocument, status, diagnostic, canonicalSite, null);
    }

    List<Invalidation> failed(DocumentId ownerDocument, ProcessorStatus status,
            ProcessorDiagnostic diagnostic, String canonicalSite, GasMeter.MultiGroupJoinResult rejectedAdmission) {
        return failed(ownerDocument, status, diagnostic, canonicalSite, rejectedAdmission, null);
    }

    List<Invalidation> failed(DocumentId ownerDocument, ProcessorStatus status,
            ProcessorDiagnostic diagnostic, String canonicalSite, GasMeter.MultiGroupJoinResult rejectedAdmission,
            GasLimitExceededException rejectedCharge) {
        if (status != ProcessorStatus.GAS_LIMIT_EXCEEDED && status != ProcessorStatus.RUNTIME_FATAL) {
            throw new IllegalArgumentException("Only recognized consuming failures terminate a same-origin attempt");
        }
        Attempt producer = attempt(ownerDocument);
        if (producer.state == State.INVALIDATED || producer.state == State.SETTLED) throw new IllegalStateException("Cannot fail discarded or published work");
        Failure prior = producer.failure;
        if (prior != null) {
            // A suspended outer runtime can settle its already-reserved child
            // prefix while unwinding the same recognized group failure. Seal
            // those member traces without re-running the admission or changing
            // its original diagnostic/site/contribution evidence.
            status = prior.status; diagnostic = prior.diagnostic; canonicalSite = prior.canonicalSite;
            rejectedAdmission = prior.rejectedAdmission;
            rejectedCharge = prior.rejectedCharge;
        }
        producer.failure = new Failure(status, Objects.requireNonNull(diagnostic, "diagnostic"),
                ClosureValueSupport.requireSha256Identity(canonicalSite, "canonicalSite"), producer.memberTraces(),
                producer.canonicalGasTrace(), producer.firstRuntime().groupAdmittedGas(), producer.firstRuntime().groupReservedGas(),
                producer.members, rejectedAdmission, rejectedAdmission == null ? Collections.emptyList() : admissionContributions.get(rejectedAdmission), rejectedCharge);
        producer.state = State.FAILURE_CANDIDATE;

        Set<Attempt> discarded = identitySet();
        Deque<Attempt> pending = new ArrayDeque<Attempt>();
        pending.add(producer);
        while (!pending.isEmpty()) {
            Attempt rejectedProducer = pending.removeFirst();
            for (Attempt candidate : currentRoots()) {
                if (candidate == producer || discarded.contains(candidate)) continue;
                for (Attempt dependency : candidate.dependencies.keySet()) {
                    if (dependency.root() == rejectedProducer) {
                        if (candidate.state == State.SETTLED) throw new IllegalStateException("Published work acquired an unfinished dependency");
                        discarded.add(candidate);
                        pending.addLast(candidate);
                        break;
                    }
                }
            }
        }
        List<Attempt> ordered = new ArrayList<Attempt>(discarded);
        ordered.sort((left, right) -> left.firstDocument().compareTo(right.firstDocument()));
        List<Invalidation> changed = new ArrayList<Invalidation>();
        for (Attempt candidate : ordered) {
            Invalidation invalidation = new Invalidation(candidate, producer.failure);
            candidate.state = State.INVALIDATED;
            for (DocumentId member : candidate.members) current.remove(member);
            candidate.closeRuntimes();
            invalidations.add(invalidation);
            changed.add(invalidation);
        }
        return Collections.unmodifiableList(changed);
    }

    /** Caller invokes this only after the canonical driver has no more work/admission sites. */
    List<Attempt> settle() {
        ensureOpen();
        List<Attempt> roots = currentRoots();
        for (Attempt value : roots) {
            if (value.state == State.RUNNING) throw new IllegalStateException("An unfinished attempt cannot be published");
            for (Attempt dependency : value.dependencies.keySet()) {
                Attempt source = dependency.root();
                if (source.state == State.FAILURE_CANDIDATE || source.state == State.INVALIDATED) {
                    throw new IllegalStateException("Conditional work survived an invalid authoritative basis");
                }
            }
        }
        for (Attempt value : roots) value.state = State.SETTLED;
        return Collections.unmodifiableList(roots);
    }

    List<Invalidation> invalidations() { return Collections.unmodifiableList(new ArrayList<Invalidation>(invalidations)); }

    List<Attempt> activeAttempts() { return Collections.unmodifiableList(currentRoots()); }

    List<GasTraceEntry> admittedTraceByStableOwner() {
        TreeMap<DocumentId, List<GasTraceEntry>> traces = new TreeMap<DocumentId, List<GasTraceEntry>>();
        for (Attempt value : currentRoots()) traces.putAll(value.memberTraces());
        List<GasTraceEntry> result = new ArrayList<GasTraceEntry>();
        for (List<GasTraceEntry> trace : traces.values()) result.addAll(trace);
        return Collections.unmodifiableList(result);
    }

    private List<Attempt> currentRoots() {
        Set<Attempt> seen = identitySet();
        List<Attempt> result = new ArrayList<Attempt>();
        for (Attempt value : current.values()) if (seen.add(value.root())) result.add(value.root());
        result.sort((left, right) -> left.firstDocument().compareTo(right.firstDocument()));
        return result;
    }

    private static <T> Set<T> identitySet() { return Collections.newSetFromMap(new IdentityHashMap<T, Boolean>()); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Same-origin attempt coordinator is closed"); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Attempt value : allAttempts) value.closeRuntimes();
    }

    static final class Attempt {
        private final long physicalOrdinal;
        private final TreeSet<DocumentId> members = new TreeSet<DocumentId>();
        private final Map<DocumentId, ManagedDocumentStepRuntime> runtimeByDocument = new TreeMap<DocumentId, ManagedDocumentStepRuntime>();
        private final Map<Attempt, String> dependencies = new IdentityHashMap<Attempt, String>();
        private final Map<DocumentId, Attempt> observedSources = new TreeMap<DocumentId, Attempt>();
        private final List<Admission> admissions = new ArrayList<Admission>();
        private final Set<ManagedDocumentStepRuntime> closedRuntimes = identitySet();
        private final Set<Attempt> originalAttempts = identitySet();
        private final List<AdmittedCharge> originalCharges = new ArrayList<AdmittedCharge>();
        private Attempt parent;
        private State state = State.RUNNING;
        private Failure failure;

        private Attempt(long physicalOrdinal, List<DocumentId> members, ManagedDocumentStepRuntime runtime) {
            this.physicalOrdinal = physicalOrdinal;
            this.originalAttempts.add(this);
            this.members.addAll(members);
            for (DocumentId member : members) runtimeByDocument.put(member, runtime);
        }
        Attempt root() { return parent == null ? this : parent.root(); }
        long physicalOrdinal() { return physicalOrdinal; }
        State state() { return root().state; }
        Set<DocumentId> members() { return Collections.unmodifiableSet(new TreeSet<DocumentId>(root().members)); }
        Failure failure() { return root().failure; }
        List<Admission> admissions() { return Collections.unmodifiableList(new ArrayList<Admission>(root().admissions)); }
        Map<DocumentId, Attempt> consumedIndependentSources() {
            Map<DocumentId, Attempt> sources = new TreeMap<>();
            for (Map.Entry<DocumentId, Attempt> observed : root().observedSources.entrySet()) {
                Attempt source = observed.getValue().root();
                if (source == root()) continue;
                if (source.state == State.INVALIDATED) throw new IllegalStateException("Invalidated source survived into a settlement dependency");
                sources.put(observed.getKey(), source);
            }
            return Collections.unmodifiableMap(sources);
        }
        List<GasTraceEntry> canonicalGasTrace() {
            List<AdmittedCharge> admitted = new ArrayList<AdmittedCharge>();
            for (Attempt original : root().originalAttempts) admitted.addAll(original.originalCharges);
            admitted.sort((left, right) -> Long.compare(left.encounterOrder, right.encounterOrder));
            List<GasTraceEntry> trace = new ArrayList<GasTraceEntry>();
            for (AdmittedCharge charge : admitted) trace.add(charge.entry);
            return Collections.unmodifiableList(trace);
        }
        DocumentId firstDocument() { return members.first(); }
        ManagedDocumentStepRuntime firstRuntime() { return runtimeByDocument.get(firstDocument()); }
        Map<DocumentId, List<GasTraceEntry>> memberTraces() {
            TreeMap<DocumentId, List<GasTraceEntry>> traces = new TreeMap<DocumentId, List<GasTraceEntry>>();
            Set<ManagedDocumentStepRuntime> seen = identitySet();
            for (Map.Entry<DocumentId, ManagedDocumentStepRuntime> entry : runtimeByDocument.entrySet()) {
                if (seen.add(entry.getValue())) traces.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<GasTraceEntry>(entry.getValue().gasTrace())));
            }
            return Collections.unmodifiableMap(traces);
        }
        void requireMutable() {
            if (state == State.INVALIDATED || state == State.SETTLED || state == State.FAILURE_CANDIDATE) {
                throw new IllegalStateException("Attempt is no longer executable");
            }
            if (state == State.SUCCESS_CANDIDATE) state = State.RUNNING;
        }
        private void closeRuntimes() {
            for (ManagedDocumentStepRuntime runtime : runtimeByDocument.values()) if (closedRuntimes.add(runtime)) runtime.close();
        }
    }

    private static final class AdmittedCharge {
        // Only selects actual canonical encounter order across original ledgers. Never encoded.
        final long encounterOrder;
        final GasTraceEntry entry;
        AdmittedCharge(long encounterOrder, GasTraceEntry entry) { this.encounterOrder = encounterOrder; this.entry = entry; }
    }

    static final class Admission {
        // Used only to retain relative order when valid branches later join;
        // this physical journal position is never part of an identity preimage.
        final long executionOrder;
        final String canonicalSite;
        final Set<DocumentId> members;
        final GasMeter.MultiGroupJoinResult admittedContributions;
        Admission(long executionOrder, String canonicalSite, Collection<DocumentId> members, GasMeter.MultiGroupJoinResult admittedContributions) {
            this.executionOrder = executionOrder;
            this.canonicalSite = canonicalSite;
            this.members = Collections.unmodifiableSet(new TreeSet<DocumentId>(members));
            this.admittedContributions = admittedContributions;
        }
    }

    static final class Failure {
        final ProcessorStatus status;
        final ProcessorDiagnostic diagnostic;
        final String canonicalSite;
        final Map<DocumentId, List<GasTraceEntry>> memberTraces;
        final List<GasTraceEntry> canonicalGasTrace;
        final long admittedGas;
        final long reservedGas;
        final Set<DocumentId> members;
        final GasMeter.MultiGroupJoinResult rejectedAdmission;
        final List<AdmissionContribution> rejectedAdmissionContributions;
        final GasLimitExceededException rejectedCharge;
        Failure(ProcessorStatus status, ProcessorDiagnostic diagnostic, String canonicalSite,
                Map<DocumentId, List<GasTraceEntry>> memberTraces, List<GasTraceEntry> canonicalGasTrace,
                long admittedGas, long reservedGas, Collection<DocumentId> members,
                GasMeter.MultiGroupJoinResult rejectedAdmission, List<AdmissionContribution> rejectedAdmissionContributions,
                GasLimitExceededException rejectedCharge) {
            this.status = status; this.diagnostic = diagnostic; this.canonicalSite = canonicalSite;
            this.memberTraces = memberTraces; this.admittedGas = admittedGas; this.reservedGas = reservedGas;
            this.canonicalGasTrace = canonicalGasTrace;
            this.members = Collections.unmodifiableSet(new TreeSet<DocumentId>(members));
            this.rejectedAdmission = rejectedAdmission;
            this.rejectedAdmissionContributions = rejectedAdmissionContributions;
            this.rejectedCharge = rejectedCharge;
        }
    }

    /** Canonical participant ownership frozen before any admission mutation or invalidation. */
    static final class AdmissionContribution {
        final Set<DocumentId> members;
        final GasMeter.GroupContribution gas;
        AdmissionContribution(Set<DocumentId> members, GasMeter.GroupContribution gas) {
            this.members = Collections.unmodifiableSet(new TreeSet<>(members)); this.gas = gas;
        }
    }

    static final class Invalidation {
        final Attempt discardedAttempt;
        final Set<DocumentId> reconstructOwnSeeds;
        final Failure rejectedProducer;
        Invalidation(Attempt discardedAttempt, Failure rejectedProducer) {
            this.discardedAttempt = discardedAttempt;
            this.reconstructOwnSeeds = discardedAttempt.members();
            this.rejectedProducer = rejectedProducer;
        }
    }
}
