package blue.language.processor.closure;

import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete immutable result of one deterministic affected-closure attempt. */
public final class ClosureProcessResult {

    private final ProcessorStatus status;
    private final String invocationIdentity;
    private final String inputClosureIdentity;
    private final String outputClosureIdentity;
    private final long graphGeneration;
    private final List<ResultingDocument> resultingDocuments;
    private final List<ComponentSnapshot> resultingComponents;
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
    private final List<GasTraceEntry> gasTrace;
    private final String gasTraceIdentity;
    private final RejectedCharge rejectedCharge;
    private final ClosureWorkOccurrence rejectedWorkOccurrence;
    private final ClosureCommitCompanion platformCommitCompanion;
    private final ProcessorDiagnostic diagnostic;

    /**
     * Creates and semantically cross-validates one complete result.
     *
     * <p>The invocation identity is compared with all occurrence and companion
     * evidence here, but its cause/environment constructor inputs live in
     * {@link ClosureInvocationInput}; an authoritative processor must validate
     * that input before supplying this identity.</p>
     *
     * @param inputSnapshot exact authoritative input used for rollback checks
     * @param status completed processor status
     * @param invocationIdentity exact invocation identity
     * @param outputClosureIdentity asserted exact output closure identity
     * @param graphGeneration resulting graph generation
     * @param resultingDocuments complete canonical resulting documents
     * @param resultingComponents complete resulting component partition
     * @param occurrenceBindings complete canonical authoritative row set
     * @param occurrenceBindingSetIdentity exact row-set identity
     * @param graphChanges committed graph-change sequence
     * @param graphChangesIdentity exact graph-change sequence identity
     * @param subscriptionDeltas committed subscription sequence
     * @param subscriptionDeltasIdentity exact subscription sequence identity
     * @param checkpointWrites committed checkpoint sequence
     * @param checkpointWritesIdentity exact checkpoint sequence identity
     * @param publicEvents explicit public Root event sequence
     * @param publicEventsIdentity exact public-event sequence identity
     * @param totalGas exact admitted gas total
     * @param gasTrace complete admitted trace
     * @param gasTraceIdentity exact admitted-trace identity
     * @param rejectedCharge rejected next charge for gas failure, otherwise null
     * @param rejectedWorkOccurrence rejected work for WORK ownership, else null
     * @param platformCommitCompanion companion required exactly for success
     * @param diagnostic optional stable processor diagnostic
     */
    public ClosureProcessResult(
            AffectedClosureSnapshot inputSnapshot,
            ProcessorStatus status,
            String invocationIdentity,
            String outputClosureIdentity,
            long graphGeneration,
            List<ResultingDocument> resultingDocuments,
            List<ComponentSnapshot> resultingComponents,
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
            List<GasTraceEntry> gasTrace,
            String gasTraceIdentity,
            RejectedCharge rejectedCharge,
            ClosureWorkOccurrence rejectedWorkOccurrence,
            ClosureCommitCompanion platformCommitCompanion,
            ProcessorDiagnostic diagnostic) {
        this(
                inputSnapshot,
                status,
                invocationIdentity,
                outputClosureIdentity,
                graphGeneration,
                resultingDocuments,
                resultingComponents,
                occurrenceBindings,
                occurrenceBindingSetIdentity,
                graphChanges,
                graphChangesIdentity,
                subscriptionDeltas,
                subscriptionDeltasIdentity,
                checkpointWrites,
                checkpointWritesIdentity,
                publicEvents,
                publicEventsIdentity,
                totalGas,
                gasTrace,
                gasTraceIdentity,
                rejectedCharge,
                rejectedWorkOccurrence,
                platformCommitCompanion,
                diagnostic,
                (ComponentFinalizationResult) null);
    }

    /**
     * Processor construction hook that reuses the exact pure finalization
     * evidence which produced the supplied result records.
     */
    ClosureProcessResult(
            AffectedClosureSnapshot inputSnapshot,
            ProcessorStatus status,
            String invocationIdentity,
            String outputClosureIdentity,
            long graphGeneration,
            List<ResultingDocument> resultingDocuments,
            List<ComponentSnapshot> resultingComponents,
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
            List<GasTraceEntry> gasTrace,
            String gasTraceIdentity,
            RejectedCharge rejectedCharge,
            ClosureWorkOccurrence rejectedWorkOccurrence,
            ClosureCommitCompanion platformCommitCompanion,
            ProcessorDiagnostic diagnostic,
            ComponentFinalizationResult reusableFinalization) {
        this(
                inputSnapshot,
                status,
                invocationIdentity,
                outputClosureIdentity,
                graphGeneration,
                resultingDocuments,
                resultingComponents,
                occurrenceBindings,
                occurrenceBindingSetIdentity,
                graphChanges,
                graphChangesIdentity,
                subscriptionDeltas,
                subscriptionDeltasIdentity,
                checkpointWrites,
                checkpointWritesIdentity,
                publicEvents,
                publicEventsIdentity,
                totalGas,
                gasTrace,
                gasTraceIdentity,
                rejectedCharge,
                rejectedWorkOccurrence,
                platformCommitCompanion,
                diagnostic,
                reusableFinalization,
                Collections.<DocumentId>emptySet());
    }

    /**
     * Admission-rejection construction hook which derives the only
     * out-of-closure gas-attribution lineages from exact candidate members.
     * Public and committing constructors retain the strict empty allowance.
     */
    ClosureProcessResult(
            AffectedClosureSnapshot inputSnapshot,
            ProcessorStatus status,
            String invocationIdentity,
            String outputClosureIdentity,
            long graphGeneration,
            List<ResultingDocument> resultingDocuments,
            List<ComponentSnapshot> resultingComponents,
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
            List<GasTraceEntry> gasTrace,
            String gasTraceIdentity,
            RejectedCharge rejectedCharge,
            ClosureWorkOccurrence rejectedWorkOccurrence,
            ClosureCommitCompanion platformCommitCompanion,
            ProcessorDiagnostic diagnostic,
            AdmissionCandidate rejectedAdmissionCandidate) {
        this(
                inputSnapshot,
                status,
                invocationIdentity,
                outputClosureIdentity,
                graphGeneration,
                resultingDocuments,
                resultingComponents,
                occurrenceBindings,
                occurrenceBindingSetIdentity,
                graphChanges,
                graphChangesIdentity,
                subscriptionDeltas,
                subscriptionDeltasIdentity,
                checkpointWrites,
                checkpointWritesIdentity,
                publicEvents,
                publicEventsIdentity,
                totalGas,
                gasTrace,
                gasTraceIdentity,
                rejectedCharge,
                rejectedWorkOccurrence,
                platformCommitCompanion,
                diagnostic,
                null,
                candidateGasDocumentIds(rejectedAdmissionCandidate));
    }

    private ClosureProcessResult(
            AffectedClosureSnapshot inputSnapshot,
            ProcessorStatus status,
            String invocationIdentity,
            String outputClosureIdentity,
            long graphGeneration,
            List<ResultingDocument> resultingDocuments,
            List<ComponentSnapshot> resultingComponents,
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
            List<GasTraceEntry> gasTrace,
            String gasTraceIdentity,
            RejectedCharge rejectedCharge,
            ClosureWorkOccurrence rejectedWorkOccurrence,
            ClosureCommitCompanion platformCommitCompanion,
            ProcessorDiagnostic diagnostic,
            ComponentFinalizationResult reusableFinalization,
            Set<DocumentId> supplementalGasDocumentIds) {
        AffectedClosureSnapshot input = Objects.requireNonNull(
                inputSnapshot, "inputSnapshot");
        this.status = Objects.requireNonNull(status, "status");
        this.invocationIdentity = identity(
                invocationIdentity, "invocationIdentity");
        this.inputClosureIdentity = identity(
                input.closureIdentity(), "inputClosureIdentity");
        this.outputClosureIdentity = identity(
                outputClosureIdentity, "outputClosureIdentity");
        this.graphGeneration = ClosureValueSupport.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.resultingDocuments = canonicalDocuments(resultingDocuments);
        this.resultingComponents = canonicalComponents(resultingComponents);
        this.occurrenceBindings = canonicalOccurrences(occurrenceBindings);
        this.occurrenceBindingSetIdentity = identity(
                occurrenceBindingSetIdentity,
                "occurrenceBindingSetIdentity");
        this.graphChanges = immutableList(graphChanges, "graphChanges");
        this.graphChangesIdentity = identity(
                graphChangesIdentity, "graphChangesIdentity");
        this.subscriptionDeltas = immutableList(
                subscriptionDeltas, "subscriptionDeltas");
        this.subscriptionDeltasIdentity = identity(
                subscriptionDeltasIdentity,
                "subscriptionDeltasIdentity");
        this.checkpointWrites = immutableList(
                checkpointWrites, "checkpointWrites");
        this.checkpointWritesIdentity = identity(
                checkpointWritesIdentity, "checkpointWritesIdentity");
        this.publicEvents = immutableList(publicEvents, "publicEvents");
        this.publicEventsIdentity = identity(
                publicEventsIdentity, "publicEventsIdentity");
        this.totalGas = ClosureValueSupport.requireSafeInteger(
                totalGas, "totalGas");
        this.gasTrace = immutableList(gasTrace, "gasTrace");
        this.gasTraceIdentity = identity(
                gasTraceIdentity, "gasTraceIdentity");
        this.rejectedCharge = rejectedCharge;
        this.rejectedWorkOccurrence = rejectedWorkOccurrence;
        this.platformCommitCompanion = platformCommitCompanion;
        this.diagnostic = diagnostic;
        if (reusableFinalization != null && !status.commits()) {
            throw new IllegalArgumentException(
                    "Reusable finalization is valid only for a committing result");
        }
        Set<DocumentId> supplementalGasDocuments =
                immutableDocumentIds(supplementalGasDocumentIds);
        if (!supplementalGasDocuments.isEmpty()
                && status != ProcessorStatus.INVALID_PROCESSING_DOCUMENT) {
            throw new IllegalArgumentException(
                    "Supplemental gas documents apply only to rejected admission candidates");
        }
        validateInputIdentity(input);
        ClosureEvidenceVerifier.verifySnapshot(input);
        validateCanonicalEvidence();
        AffectedClosureSnapshot output = validateResultSnapshot();
        validateStatusAndRollback(input);
        validateRejectedCharge();
        ClosureEvidenceVerifier.verifyTransition(
                input,
                output,
                this.resultingDocuments,
                this.graphChanges,
                this.subscriptionDeltas,
                this.checkpointWrites,
                this.publicEvents,
                this.gasTrace,
                reusableFinalization,
                supplementalGasDocuments);
        if (platformCommitCompanion != null) {
            validateCompanion(input, platformCommitCompanion);
        }
    }

    private static Set<DocumentId> candidateGasDocumentIds(
            AdmissionCandidate candidate) {
        AdmissionCandidate selected = Objects.requireNonNull(
                candidate, "rejectedAdmissionCandidate");
        if (!(selected instanceof
                AdmissionCandidate.AmbiguousPreliminaryMembers)) {
            return Collections.emptySet();
        }
        HashSet<DocumentId> result = new HashSet<DocumentId>();
        for (AdmissionCandidate.CandidateCyclicMember member
                : ((AdmissionCandidate.AmbiguousPreliminaryMembers) selected)
                        .candidateCyclicMembers()) {
            result.add(member.documentId());
        }
        return result;
    }

    private static Set<DocumentId> immutableDocumentIds(
            Set<DocumentId> values) {
        HashSet<DocumentId> result = new HashSet<DocumentId>();
        for (DocumentId value : Objects.requireNonNull(
                values, "supplementalGasDocumentIds")) {
            result.add(Objects.requireNonNull(
                    value, "supplemental gas documentId"));
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Returns completed processor status.
     *
     * @return completed processor status
     */
    public ProcessorStatus status() {
        return status;
    }

    /**
     * Returns whether the result commits atomically.
     *
     * @return whether the result commits atomically
     */
    public boolean commits() {
        return status.commits();
    }

    /**
     * Returns literal {@code true}; closure results are always atomic.
     *
     * @return literal {@code true}; closure results are always atomic
     */
    public boolean atomic() {
        return true;
    }

    /**
     * Returns exact invocation identity.
     *
     * @return exact invocation identity
     */
    public String invocationIdentity() {
        return invocationIdentity;
    }

    /**
     * Returns exact input closure identity.
     *
     * @return exact input closure identity
     */
    public String inputClosureIdentity() {
        return inputClosureIdentity;
    }

    /**
     * Returns exact output closure identity.
     *
     * @return exact output closure identity
     */
    public String outputClosureIdentity() {
        return outputClosureIdentity;
    }

    /**
     * Returns resulting graph generation.
     *
     * @return resulting graph generation
     */
    public long graphGeneration() {
        return graphGeneration;
    }

    /**
     * Returns immutable canonical complete resulting documents.
     *
     * @return immutable canonical complete resulting documents
     */
    public List<ResultingDocument> resultingDocuments() {
        return resultingDocuments;
    }

    /**
     * Returns immutable complete resulting component partition.
     *
     * @return immutable complete resulting component partition
     */
    public List<ComponentSnapshot> resultingComponents() {
        return resultingComponents;
    }

    /**
     * Returns immutable canonical complete occurrence row set.
     *
     * @return immutable canonical complete occurrence row set
     */
    public List<ManagedOccurrenceBinding> occurrenceBindings() {
        return occurrenceBindings;
    }

    /**
     * Returns exact complete occurrence-row-set identity.
     *
     * @return exact complete occurrence-row-set identity
     */
    public String occurrenceBindingSetIdentity() {
        return occurrenceBindingSetIdentity;
    }

    /**
     * Returns immutable committed graph-change sequence.
     *
     * @return immutable committed graph-change sequence
     */
    public List<GraphChange> graphChanges() {
        return graphChanges;
    }

    /**
     * Returns exact graph-change sequence identity.
     *
     * @return exact graph-change sequence identity
     */
    public String graphChangesIdentity() {
        return graphChangesIdentity;
    }

    /**
     * Returns immutable committed subscription-delta sequence.
     *
     * @return immutable committed subscription-delta sequence
     */
    public List<SubscriptionDelta> subscriptionDeltas() {
        return subscriptionDeltas;
    }

    /**
     * Returns exact subscription-delta sequence identity.
     *
     * @return exact subscription-delta sequence identity
     */
    public String subscriptionDeltasIdentity() {
        return subscriptionDeltasIdentity;
    }

    /**
     * Returns immutable committed checkpoint-write sequence.
     *
     * @return immutable committed checkpoint-write sequence
     */
    public List<CheckpointWrite> checkpointWrites() {
        return checkpointWrites;
    }

    /**
     * Returns exact checkpoint-write sequence identity.
     *
     * @return exact checkpoint-write sequence identity
     */
    public String checkpointWritesIdentity() {
        return checkpointWritesIdentity;
    }

    /**
     * Returns immutable explicit public-event occurrences.
     *
     * @return immutable explicit public-event occurrences
     */
    public List<PublicEventOccurrence> publicEvents() {
        return publicEvents;
    }

    /**
     * Returns exact public-event sequence identity.
     *
     * @return exact public-event sequence identity
     */
    public String publicEventsIdentity() {
        return publicEventsIdentity;
    }

    /**
     * Returns whether this result exposes literal authoritative input state.
     *
     * @return whether this result exposes literal authoritative input state
     */
    public boolean rollbackToInput() {
        return !status.commits();
    }

    /**
     * Returns exact admitted gas total.
     *
     * @return exact admitted gas total
     */
    public long totalGas() {
        return totalGas;
    }

    /**
     * Returns immutable complete admitted gas trace.
     *
     * @return immutable complete admitted gas trace
     */
    public List<GasTraceEntry> gasTrace() {
        return gasTrace;
    }

    /**
     * Returns exact admitted gas-trace identity.
     *
     * @return exact admitted gas-trace identity
     */
    public String gasTraceIdentity() {
        return gasTraceIdentity;
    }

    /**
     * Returns complete rejected charge for gas failure, otherwise null.
     *
     * @return complete rejected charge for gas failure, otherwise null
     */
    public RejectedCharge rejectedCharge() {
        return rejectedCharge;
    }

    /**
     * Returns rejected work exactly for WORK-owned rejection.
     *
     * @return rejected work exactly for WORK-owned rejection
     */
    public ClosureWorkOccurrence rejectedWorkOccurrence() {
        return rejectedWorkOccurrence;
    }

    /**
     * Returns exact atomic commit companion, or null for rollback.
     *
     * @return exact atomic commit companion, or null for rollback
     */
    public ClosureCommitCompanion platformCommitCompanion() {
        return platformCommitCompanion;
    }

    /**
     * Compatibility alias for {@link #platformCommitCompanion()}.
     *
     * @return exact atomic commit companion, or null
     */
    public ClosureCommitCompanion commitCompanion() {
        return platformCommitCompanion;
    }

    /**
     * Returns optional stable processor diagnostic.
     *
     * @return optional stable processor diagnostic
     */
    public ProcessorDiagnostic diagnostic() {
        return diagnostic;
    }

    private void validateInputIdentity(AffectedClosureSnapshot input) {
        String computed = ClosureIdentityService.INSTANCE
                .affectedClosureIdentity(input);
        if (!inputClosureIdentity.equals(computed)) {
            throw new IllegalArgumentException(
                    "inputClosureIdentity does not identify inputSnapshot");
        }
    }

    private void validateCanonicalEvidence() {
        requireOrdinals();
        validateSequenceIdentities();
        validateGasTrace();
    }

    private void requireOrdinals() {
        for (int index = 0; index < graphChanges.size(); index++) {
            if (graphChanges.get(index).graphChangeOrdinal() != index) {
                throw new IllegalArgumentException("graphChange ordinal gap");
            }
        }
        for (int index = 0; index < subscriptionDeltas.size(); index++) {
            if (subscriptionDeltas.get(index)
                    .subscriptionDeltaOrdinal() != index) {
                throw new IllegalArgumentException(
                        "subscriptionDelta ordinal gap");
            }
        }
        for (int index = 0; index < checkpointWrites.size(); index++) {
            if (checkpointWrites.get(index).checkpointWriteOrdinal() != index) {
                throw new IllegalArgumentException(
                        "checkpointWrite ordinal gap");
            }
        }
        for (int index = 0; index < publicEvents.size(); index++) {
            if (publicEvents.get(index).publicEventOrdinal() != index) {
                throw new IllegalArgumentException("publicEvent ordinal gap");
            }
        }
    }

    private void validateSequenceIdentities() {
        requireSequenceIdentity(
                ClosureIdentityService.Constructor.GRAPH_CHANGES,
                identityValues(graphChanges),
                graphChangesIdentity,
                "graphChangesIdentity");
        requireSequenceIdentity(
                ClosureIdentityService.Constructor.SUBSCRIPTION_DELTAS,
                subscriptionIdentityValues(subscriptionDeltas),
                subscriptionDeltasIdentity,
                "subscriptionDeltasIdentity");
        requireSequenceIdentity(
                ClosureIdentityService.Constructor.CHECKPOINT_WRITES,
                checkpointIdentityValues(checkpointWrites),
                checkpointWritesIdentity,
                "checkpointWritesIdentity");
        requireSequenceIdentity(
                ClosureIdentityService.Constructor.PUBLIC_EVENTS,
                publicEventIdentityValues(publicEvents),
                publicEventsIdentity,
                "publicEventsIdentity");
        requireSequenceIdentity(
                ClosureIdentityService.Constructor.GAS_TRACE,
                gasIdentityValues(gasTrace),
                gasTraceIdentity,
                "gasTraceIdentity");
    }

    private void validateGasTrace() {
        long total = 0L;
        for (int index = 0; index < gasTrace.size(); index++) {
            GasTraceEntry entry = gasTrace.get(index);
            if (entry.sequence() != index) {
                throw new IllegalArgumentException("gas trace sequence gap");
            }
            try {
                total = Math.addExact(total, entry.subtotal());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "gas trace total overflow", exception);
            }
            ClosureValueSupport.requireSafeInteger(total, "gas trace total");
        }
        if (total != totalGas) {
            throw new IllegalArgumentException(
                    "totalGas does not equal admitted gas trace");
        }
    }

    private AffectedClosureSnapshot validateResultSnapshot() {
        Map<String, ComponentSnapshot> components =
                new HashMap<String, ComponentSnapshot>();
        Set<DocumentId> covered = new HashSet<DocumentId>();
        for (ComponentSnapshot component : resultingComponents) {
            if (components.put(component.componentIdentity(), component) != null) {
                throw new IllegalArgumentException(
                        "Duplicate resulting component identity");
            }
            covered.addAll(component.orderedMemberDocumentIds());
        }
        Set<DocumentId> documents = new HashSet<DocumentId>();
        List<ManagedDocumentSnapshot> snapshots =
                new ArrayList<ManagedDocumentSnapshot>();
        List<DocumentId> publicRoots = new ArrayList<DocumentId>();
        for (ResultingDocument document : resultingDocuments) {
            documents.add(document.documentId());
            snapshots.add(document.asSnapshot());
            if (document.publicRoot()) {
                publicRoots.add(document.documentId());
            }
            ComponentSnapshot component = components.get(
                    document.componentIdentity());
            if (component == null
                    || !component.componentStateIdentity().equals(
                            document.componentStateIdentity())
                    || component.componentGeneration()
                    != document.componentGeneration()) {
                throw new IllegalArgumentException(
                        "Resulting document component evidence disagrees");
            }
            int member = component.orderedMemberDocumentIds().indexOf(
                    document.documentId());
            if (member < 0 || !component.orderedMemberBlueIds().get(member)
                    .equals(document.afterBlueId())) {
                throw new IllegalArgumentException(
                        "Resulting document is absent from its component state");
            }
            Long expectedIndex = component.kind() == ComponentKind.CYCLIC
                    ? parseMemberIndex(document.afterBlueId()) : null;
            if (!Objects.equals(expectedIndex, document.memberIndex())) {
                throw new IllegalArgumentException(
                        "Resulting document memberIndex disagrees with component");
            }
        }
        if (!documents.equals(covered)) {
            throw new IllegalArgumentException(
                    "Resulting components do not partition resulting documents");
        }
        String bindingIdentity = ClosureIdentityService.INSTANCE
                .occurrenceBindingSetIdentity(occurrenceBindings);
        if (!occurrenceBindingSetIdentity.equals(bindingIdentity)) {
            throw new IllegalArgumentException(
                    "occurrenceBindingSetIdentity does not identify row set");
        }
        AffectedClosureSnapshot output = new AffectedClosureSnapshot(
                outputClosureIdentity,
                graphGeneration,
                snapshots,
                occurrenceBindings,
                occurrenceBindingSetIdentity,
                resultingComponents,
                publicRoots);
        String computedOutput = ClosureIdentityService.INSTANCE
                .affectedClosureIdentity(output);
        if (!outputClosureIdentity.equals(computedOutput)) {
            throw new IllegalArgumentException(
                    "outputClosureIdentity does not identify result state");
        }
        Set<DocumentId> publicRootSet = new HashSet<DocumentId>(publicRoots);
        for (PublicEventOccurrence event : publicEvents) {
            if (!publicRootSet.contains(event.publicRootDocumentId())) {
                throw new IllegalArgumentException(
                        "Public event does not target a resulting public Root");
            }
            String expected = ClosureIdentityService.INSTANCE
                    .eventOccurrenceIdentity(
                            invocationIdentity,
                            event.eventOccurrenceOrdinal(),
                            event.eventBlueId());
            if (!expected.equals(event.eventOccurrenceIdentity())) {
                throw new IllegalArgumentException(
                        "eventOccurrenceIdentity does not identify public event");
            }
        }
        return output;
    }

    private void validateStatusAndRollback(AffectedClosureSnapshot input) {
        if (status.commits() != (platformCommitCompanion != null)) {
            throw new IllegalArgumentException(
                    "Commit companion must be present exactly for success");
        }
        if (status.commits()) {
            return;
        }
        if (!inputClosureIdentity.equals(outputClosureIdentity)
                || graphGeneration != input.graphGeneration()
                || !graphChanges.isEmpty()
                || !subscriptionDeltas.isEmpty()
                || !checkpointWrites.isEmpty()
                || !publicEvents.isEmpty()) {
            throw new IllegalArgumentException(
                    "Non-success result must roll back all committed effects");
        }
        requireInputDocuments(input);
        requireInputComponents(input);
        requireInputOccurrences(input);
    }

    private void validateRejectedCharge() {
        boolean gasFailure = status == ProcessorStatus.GAS_LIMIT_EXCEEDED;
        if (gasFailure != (rejectedCharge != null)) {
            throw new IllegalArgumentException(
                    "Rejected charge is required exactly for gas failure");
        }
        if (rejectedCharge == null) {
            if (rejectedWorkOccurrence != null) {
                throw new IllegalArgumentException(
                        "Rejected work requires a rejected charge");
            }
            return;
        }
        RejectedCharge.Owner owner = rejectedCharge.owner();
        boolean workOwned = owner.kind() == RejectedCharge.Owner.Kind.WORK;
        if (workOwned != (rejectedWorkOccurrence != null)) {
            throw new IllegalArgumentException(
                    "Rejected work must appear exactly for WORK ownership");
        }
        if (workOwned) {
            if (!owner.workOccurrenceIdentity().equals(
                    rejectedWorkOccurrence.workIdentity())) {
                throw new IllegalArgumentException(
                        "Rejected work evidence disagrees with charge owner");
            }
            validateWorkIdentity(rejectedWorkOccurrence);
        }
        if (rejectedCharge.applicableCap().kind()
                == RejectedCharge.ApplicableCap.Kind.LOCAL
                && !containsDocument(
                        rejectedCharge.applicableCap().documentId())) {
            throw new IllegalArgumentException(
                    "Local rejected cap names a document outside the closure");
        }
    }

    private void validateCompanion(
            AffectedClosureSnapshot input,
            ClosureCommitCompanion companion) {
        if (!invocationIdentity.equals(companion.invocationIdentity())
                || !inputClosureIdentity.equals(
                        companion.inputClosureIdentity())
                || !outputClosureIdentity.equals(
                        companion.outputClosureIdentity())
                || input.graphGeneration()
                != companion.expectedInputGraphGeneration()
                || graphGeneration != companion.outputGraphGeneration()
                || !input.occurrenceBindingSetIdentity().equals(
                        companion.inputOccurrenceBindingSetIdentity())
                || !occurrenceBindingSetIdentity.equals(
                        companion.occurrenceBindingSetIdentity())
                || !graphChangesIdentity.equals(
                        companion.graphChangesIdentity())
                || !checkpointWritesIdentity.equals(
                        companion.checkpointWritesIdentity())
                || !subscriptionDeltasIdentity.equals(
                        companion.subscriptionDeltasIdentity())
                || !publicEventsIdentity.equals(
                        companion.publicEventsIdentity())
                || !gasTraceIdentity.equals(companion.gasTraceIdentity())) {
            throw new IllegalArgumentException(
                    "Commit companion disagrees with result identities");
        }
        requireCompanionInputDocuments(input, companion);
        requireCompanionInputComponents(input, companion);
        requireCompanionResultDocuments(companion);
        requireCompanionResultComponents(companion);
    }

    private void requireInputDocuments(AffectedClosureSnapshot input) {
        if (resultingDocuments.size() != input.managedDocuments().size()) {
            throw new IllegalArgumentException(
                    "Rollback must expose every exact input document");
        }
        for (int index = 0; index < resultingDocuments.size(); index++) {
            ResultingDocument result = resultingDocuments.get(index);
            ManagedDocumentSnapshot expected = input.managedDocuments().get(index);
            if (!result.documentId().equals(expected.documentId())
                    || !result.beforeBlueId().equals(expected.blueId())
                    || !result.afterBlueId().equals(expected.blueId())
                    || result.initialized() != expected.initialized()
                    || result.terminated() != expected.terminated()
                    || result.publicRoot() != expected.publicRoot()
                    || result.epoch() != expected.epoch()
                    || result.componentGeneration()
                    != expected.componentGeneration()) {
                throw new IllegalArgumentException(
                        "Rollback document differs from authoritative input");
            }
        }
    }

    private void requireInputComponents(AffectedClosureSnapshot input) {
        if (resultingComponents.size() != input.components().size()) {
            throw new IllegalArgumentException(
                    "Rollback must expose exact input components");
        }
        for (int index = 0; index < resultingComponents.size(); index++) {
            ComponentSnapshot left = resultingComponents.get(index);
            ComponentSnapshot right = input.components().get(index);
            if (!sameComponent(left, right)) {
                throw new IllegalArgumentException(
                        "Rollback component differs from authoritative input");
            }
        }
    }

    private void requireInputOccurrences(AffectedClosureSnapshot input) {
        if (occurrenceBindings.size() != input.occurrences().size()) {
            throw new IllegalArgumentException(
                    "Rollback must expose exact input occurrence rows");
        }
        for (int index = 0; index < occurrenceBindings.size(); index++) {
            if (!sameOccurrence(
                    occurrenceBindings.get(index),
                    input.occurrences().get(index))) {
                throw new IllegalArgumentException(
                        "Rollback occurrence differs from authoritative input");
            }
        }
    }

    private void requireCompanionInputDocuments(
            AffectedClosureSnapshot input,
            ClosureCommitCompanion companion) {
        if (companion.expectedInputDocuments().size()
                != input.managedDocuments().size()) {
            throw new IllegalArgumentException(
                    "Companion input document expectations are incomplete");
        }
        for (int index = 0; index < input.managedDocuments().size(); index++) {
            ManagedDocumentSnapshot expected = input.managedDocuments().get(index);
            ClosureCommitCompanion.InputDocument actual =
                    companion.expectedInputDocuments().get(index);
            if (!expected.documentId().equals(actual.documentId())
                    || !expected.blueId().equals(actual.blueId())) {
                throw new IllegalArgumentException(
                        "Companion input document expectation disagrees");
            }
        }
    }

    private void requireCompanionInputComponents(
            AffectedClosureSnapshot input,
            ClosureCommitCompanion companion) {
        if (companion.expectedInputComponents().size()
                != input.components().size()) {
            throw new IllegalArgumentException(
                    "Companion input component expectations are incomplete");
        }
        for (int index = 0; index < input.components().size(); index++) {
            ComponentSnapshot expected = input.components().get(index);
            ClosureCommitCompanion.InputComponent actual =
                    companion.expectedInputComponents().get(index);
            if (!expected.componentIdentity().equals(
                        actual.componentIdentity())
                    || !expected.componentStateIdentity().equals(
                            actual.componentStateIdentity())
                    || expected.componentGeneration()
                    != actual.componentGeneration()
                    || !Objects.equals(
                            expected.masterBlueId(), actual.masterBlueId())) {
                throw new IllegalArgumentException(
                        "Companion input component expectation disagrees");
            }
        }
    }

    private void requireCompanionResultDocuments(
            ClosureCommitCompanion companion) {
        List<ClosureCommitCompanion.DocumentDelta> expected =
                new ArrayList<ClosureCommitCompanion.DocumentDelta>();
        for (ResultingDocument document : resultingDocuments) {
            expected.add(new ClosureCommitCompanion.DocumentDelta(
                    document.documentId(),
                    document.beforeBlueId(),
                    document.afterBlueId()));
        }
        if (expected.size() != companion.resultingDocuments().size()) {
            throw new IllegalArgumentException(
                    "Companion resulting document deltas are incomplete");
        }
        for (int index = 0; index < expected.size(); index++) {
            ClosureCommitCompanion.DocumentDelta left = expected.get(index);
            ClosureCommitCompanion.DocumentDelta right =
                    companion.resultingDocuments().get(index);
            if (!left.documentId().equals(right.documentId())
                    || !left.beforeBlueId().equals(right.beforeBlueId())
                    || !left.afterBlueId().equals(right.afterBlueId())) {
                throw new IllegalArgumentException(
                        "Companion resulting document delta disagrees");
            }
        }
    }

    private void requireCompanionResultComponents(
            ClosureCommitCompanion companion) {
        if (resultingComponents.size()
                != companion.resultingComponents().size()) {
            throw new IllegalArgumentException(
                    "Companion resulting components are incomplete");
        }
        for (int index = 0; index < resultingComponents.size(); index++) {
            ComponentSnapshot expected = resultingComponents.get(index);
            ClosureCommitCompanion.ResultComponent actual =
                    companion.resultingComponents().get(index);
            if (!expected.componentIdentity().equals(
                        actual.componentIdentity())
                    || !expected.componentStateIdentity().equals(
                            actual.componentStateIdentity())
                    || !Objects.equals(
                            expected.cyclicProofIdentity(),
                            actual.cyclicProofIdentity())) {
                throw new IllegalArgumentException(
                        "Companion resulting component disagrees");
            }
        }
    }

    private void validateWorkIdentity(ClosureWorkOccurrence work) {
        String targetScopeIdentity = ClosureIdentityService.INSTANCE
                .managedScopeKeyIdentity(work.targetManagedScopeKey());
        if (!targetScopeIdentity.equals(
                work.targetManagedScopeIdentity())
                || !containsDocument(work.targetDocumentId())) {
            throw new IllegalArgumentException(
                    "Rejected work target Root is not authoritative");
        }
        String expected = ClosureIdentityService.INSTANCE
                .workOccurrenceIdentity(
                        invocationIdentity,
                        work.ordinal(),
                        work.kind(),
                        work.targetManagedScopeIdentity(),
                        work.sourceOccurrenceIdentity());
        if (!expected.equals(work.workIdentity())) {
            throw new IllegalArgumentException(
                    "workIdentity does not identify work occurrence");
        }
    }

    private boolean containsDocument(DocumentId documentId) {
        for (ResultingDocument document : resultingDocuments) {
            if (document.documentId().equals(documentId)) {
                return true;
            }
        }
        return false;
    }

    private void requireSequenceIdentity(
            ClosureIdentityService.Constructor constructor,
            List<Object> values,
            String asserted,
            String field) {
        String computed = ClosureIdentityService.INSTANCE.identity(
                constructor, values);
        if (!asserted.equals(computed)) {
            throw new IllegalArgumentException(
                    field + " does not identify its complete sequence");
        }
    }

    private static List<Object> identityValues(List<GraphChange> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (GraphChange value : values) {
            result.add(value.identityValue());
        }
        return result;
    }

    private static List<Object> subscriptionIdentityValues(
            List<SubscriptionDelta> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (SubscriptionDelta value : values) {
            result.add(value.identityValue());
        }
        return result;
    }

    private static List<Object> checkpointIdentityValues(
            List<CheckpointWrite> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (CheckpointWrite value : values) {
            result.add(value.identityValue());
        }
        return result;
    }

    private static List<Object> publicEventIdentityValues(
            List<PublicEventOccurrence> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (PublicEventOccurrence value : values) {
            result.add(value.identityValue());
        }
        return result;
    }

    private static List<Object> gasIdentityValues(List<GasTraceEntry> values) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (GasTraceEntry value : values) {
            result.add(value.identityValue());
        }
        return result;
    }

    private static Long parseMemberIndex(String blueId) {
        int separator = blueId.lastIndexOf('#');
        if (separator < 0) {
            throw new IllegalArgumentException(
                    "Cyclic component member lacks #n suffix");
        }
        try {
            long value = Long.parseLong(blueId.substring(separator + 1));
            return Long.valueOf(ClosureValueSupport.requireSafeInteger(
                    value, "memberIndex"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Cyclic component member has invalid #n suffix",
                    exception);
        }
    }

    private static boolean sameComponent(
            ComponentSnapshot left,
            ComponentSnapshot right) {
        return left.componentIdentity().equals(right.componentIdentity())
                && left.componentStateIdentity().equals(
                        right.componentStateIdentity())
                && left.componentGeneration() == right.componentGeneration()
                && left.kind() == right.kind()
                && left.orderedMemberDocumentIds().equals(
                        right.orderedMemberDocumentIds())
                && left.orderedMemberBlueIds().equals(
                        right.orderedMemberBlueIds())
                && Objects.equals(left.masterBlueId(), right.masterBlueId())
                && Objects.equals(
                        left.cyclicProofIdentity(),
                        right.cyclicProofIdentity());
    }

    private static boolean sameOccurrence(
            ManagedOccurrenceBinding left,
            ManagedOccurrenceBinding right) {
        return left.occurrenceIdentity().equals(right.occurrenceIdentity())
                && left.bindingIdentity().equals(right.bindingIdentity())
                && left.bindingPolicyIdentity().equals(
                        right.bindingPolicyIdentity())
                && left.sourceDocumentId().equals(right.sourceDocumentId())
                && left.sourcePath().equals(right.sourcePath())
                && left.activationGeneration() == right.activationGeneration()
                && left.targetDocumentId().equals(right.targetDocumentId())
                && left.expectedTargetBlueId().equals(
                        right.expectedTargetBlueId())
                && left.active() == right.active()
                && Objects.equals(
                        left.pendingHistoricalEpoch(),
                        right.pendingHistoricalEpoch());
    }

    private static String identity(String value, String field) {
        return ClosureValueSupport.requireSha256Identity(value, field);
    }

    private static <T> List<T> immutableList(
            List<T> values,
            String field) {
        ArrayList<T> copy = new ArrayList<T>(
                Objects.requireNonNull(values, field));
        for (T item : copy) {
            Objects.requireNonNull(item, field + " item");
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<ResultingDocument> canonicalDocuments(
            List<ResultingDocument> values) {
        List<ResultingDocument> copy = immutableList(
                values, "resultingDocuments");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "resultingDocuments must not be empty");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "resultingDocuments are not in canonical order");
            }
        }
        return copy;
    }

    private static List<ComponentSnapshot> canonicalComponents(
            List<ComponentSnapshot> values) {
        List<ComponentSnapshot> copy = immutableList(
                values, "resultingComponents");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "resultingComponents must not be empty");
        }
        Set<String> identities = new HashSet<String>();
        for (ComponentSnapshot component : copy) {
            if (!identities.add(component.componentIdentity())) {
                throw new IllegalArgumentException(
                        "resultingComponents contain a duplicate identity");
            }
        }
        return copy;
    }

    private static List<ManagedOccurrenceBinding> canonicalOccurrences(
            List<ManagedOccurrenceBinding> values) {
        List<ManagedOccurrenceBinding> copy = immutableList(
                values, "occurrenceBindings");
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "occurrenceBindings are not in canonical order");
            }
        }
        return copy;
    }
}
