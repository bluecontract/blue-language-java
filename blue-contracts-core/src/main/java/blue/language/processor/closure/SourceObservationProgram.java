package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.Node;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.FrozenJsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;

/**
 * Immutable source actions at the interpreter's real continuation boundaries.
 *
 * <p>Steps are stored in entry order, including synchronous nested steps. Each
 * step retains the patch/enqueue sequence reached by its own continuation.
 * This is deliberately neither a final snapshot nor a flat event replay.</p>
 *
 * <p>Only a completed library execution can construct this capability. An
 * external store must authenticate its association with the source result
 * before supplying it again. Business handlers are not called when its
 * retained source steps are interpreted in a consumer operation.</p>
 */
public final class SourceObservationProgram {
    private final String invocationIdentity;
    private final ProcessingCause.Kind causeKind;
    private final String causeIdentity;
    private final ExternalEventCause externalCause;
    private final ClosureEnvironment environment;
    private final ExecutionPolicy executionPolicy;
    private final List<SourceState> sourcePredecessors;
    private final List<SourceState> sourceResults;
    private final Set<DocumentId> ownedDocumentIds;
    private final List<Step> steps;
    private final List<SkippedWork> skippedWork;
    private final List<ReferenceProjection> referenceProjections;
    private final List<ManagedOccurrenceBinding> sourceBeforeBindings, sourceAfterBindings;
    private final List<ComponentSnapshot> sourceBeforeComponents, sourceAfterComponents;
    private final List<SourceObservationProgram> borrowedPrograms;
    private final List<ManagedReadPin> sourceReadPins;
    private final List<AcceptedAttachmentView> acceptedViews;
    private final List<AcceptedInitializationInstallation> acceptedInitializations;
    private final ManagedReactionContext managedReaction;

    SourceObservationProgram(ClosureInvocationInput input,
                             ClosureProcessResult result,
                             List<Step> steps) {
        this(input, result, steps, ids(input));
    }

    SourceObservationProgram(ClosureInvocationInput input, ClosureProcessResult result,
                             List<Step> steps, Set<DocumentId> owned) {
        this(input, result, steps, owned, Collections.<ReferenceProjection>emptyList());
    }

    SourceObservationProgram(ClosureInvocationInput input, ClosureProcessResult result,
                             List<Step> steps, Set<DocumentId> owned, List<ReferenceProjection> projections) {
        this(input, result, steps, owned, projections, Collections.<SourceObservationProgram>emptyList());
    }

    SourceObservationProgram(ClosureInvocationInput input, ClosureProcessResult result,
                             List<Step> steps, Set<DocumentId> owned, List<ReferenceProjection> projections,
                             List<SourceObservationProgram> borrowed) {
        this(input, result, steps, owned, projections, borrowed, Collections.emptyList());
    }

    SourceObservationProgram(ClosureInvocationInput input, ClosureProcessResult result,
                             List<Step> steps, Set<DocumentId> owned, List<ReferenceProjection> projections,
                             List<SourceObservationProgram> borrowed, List<AcceptedAttachmentView> acceptedViews) {
        this(input, result, steps, owned, projections, borrowed, acceptedViews, Collections.emptyList());
    }

    SourceObservationProgram(ClosureInvocationInput input, ClosureProcessResult result,
                             List<Step> steps, Set<DocumentId> owned, List<ReferenceProjection> projections,
                             List<SourceObservationProgram> borrowed, List<AcceptedAttachmentView> acceptedViews,
                             List<AcceptedInitializationInstallation> acceptedInitializations) {
        this(input, result, steps, owned, projections, borrowed, acceptedViews, acceptedInitializations, Collections.emptyList());
    }

    SourceObservationProgram(ClosureInvocationInput input, ClosureProcessResult result,
                             List<Step> steps, Set<DocumentId> owned, List<ReferenceProjection> projections,
                             List<SourceObservationProgram> borrowed, List<AcceptedAttachmentView> acceptedViews,
                             List<AcceptedInitializationInstallation> acceptedInitializations, List<SkippedWork> skippedWork) {
        this(input.invocationIdentity(), input.cause().kind(), input.cause().causeIdentity(),
                input.cause() instanceof ExternalEventCause ? (ExternalEventCause) input.cause() : null,
                input.environment(), input.executionPolicy(), before(input, owned), after(result, owned), owned, steps, projections,
                ownedBindings(input.snapshot().occurrences(), owned), ownedBindings(result.occurrenceBindings(), owned),
                ownedComponents(input.snapshot().components(), owned), ownedComponents(result.resultingComponents(), owned),
                borrowed, retainedPins(acceptedViews, acceptedInitializations), acceptedViews, input.managedReaction().orElse(null), acceptedInitializations, skippedWork);
        if (!result.commits()
                || !input.invocationIdentity().equals(result.invocationIdentity())) {
            throw new IllegalArgumentException("Observation program requires its successful source result");
        }
    }

    SourceObservationProgram(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
                             ExternalEventCause externalCause, ClosureEnvironment environment,
                             ExecutionPolicy executionPolicy, List<SourceState> predecessors,
                             List<SourceState> results, Set<DocumentId> owned, List<Step> steps) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy,
                predecessors, results, owned, steps, Collections.<ReferenceProjection>emptyList());
    }

    SourceObservationProgram(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
                             ExternalEventCause externalCause, ClosureEnvironment environment,
                             ExecutionPolicy executionPolicy, List<SourceState> predecessors,
                             List<SourceState> results, Set<DocumentId> owned, List<Step> steps,
                             List<ReferenceProjection> projections) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy,
                predecessors, results, owned, steps, projections, Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.<ManagedOccurrenceBinding>emptyList(), Collections.<ComponentSnapshot>emptyList(),
                Collections.<ComponentSnapshot>emptyList());
    }

    SourceObservationProgram(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
                             ExternalEventCause externalCause, ClosureEnvironment environment,
                             ExecutionPolicy executionPolicy, List<SourceState> predecessors,
                             List<SourceState> results, Set<DocumentId> owned, List<Step> steps,
                             List<ReferenceProjection> projections, List<ManagedOccurrenceBinding> beforeBindings,
                             List<ManagedOccurrenceBinding> afterBindings, List<ComponentSnapshot> beforeComponents,
                             List<ComponentSnapshot> afterComponents) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy,
                predecessors, results, owned, steps, projections, beforeBindings, afterBindings, beforeComponents,
                afterComponents, Collections.<SourceObservationProgram>emptyList(), Collections.<ManagedReadPin>emptyList());
    }

    SourceObservationProgram(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
                             ExternalEventCause externalCause, ClosureEnvironment environment,
                             ExecutionPolicy executionPolicy, List<SourceState> predecessors,
                             List<SourceState> results, Set<DocumentId> owned, List<Step> steps,
                             List<ReferenceProjection> projections, List<ManagedOccurrenceBinding> beforeBindings,
                             List<ManagedOccurrenceBinding> afterBindings, List<ComponentSnapshot> beforeComponents,
                             List<ComponentSnapshot> afterComponents, List<SourceObservationProgram> borrowed,
                             List<ManagedReadPin> readPins) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy,
                predecessors, results, owned, steps, projections, beforeBindings, afterBindings,
                beforeComponents, afterComponents, borrowed, readPins, Collections.emptyList());
    }

    SourceObservationProgram(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
                             ExternalEventCause externalCause, ClosureEnvironment environment,
                             ExecutionPolicy executionPolicy, List<SourceState> predecessors,
                             List<SourceState> results, Set<DocumentId> owned, List<Step> steps,
                             List<ReferenceProjection> projections, List<ManagedOccurrenceBinding> beforeBindings,
                             List<ManagedOccurrenceBinding> afterBindings, List<ComponentSnapshot> beforeComponents,
                             List<ComponentSnapshot> afterComponents, List<SourceObservationProgram> borrowed,
                             List<ManagedReadPin> readPins, List<AcceptedAttachmentView> acceptedViews) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy,
                predecessors, results, owned, steps, projections, beforeBindings, afterBindings,
                beforeComponents, afterComponents, borrowed, readPins, acceptedViews, null);
    }

    SourceObservationProgram(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
                             ExternalEventCause externalCause, ClosureEnvironment environment,
                             ExecutionPolicy executionPolicy, List<SourceState> predecessors,
                             List<SourceState> results, Set<DocumentId> owned, List<Step> steps,
                             List<ReferenceProjection> projections, List<ManagedOccurrenceBinding> beforeBindings,
                             List<ManagedOccurrenceBinding> afterBindings, List<ComponentSnapshot> beforeComponents,
                             List<ComponentSnapshot> afterComponents, List<SourceObservationProgram> borrowed,
                             List<ManagedReadPin> readPins, List<AcceptedAttachmentView> acceptedViews,
                             ManagedReactionContext managedReaction) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy,
                predecessors, results, owned, steps, projections, beforeBindings, afterBindings,
                beforeComponents, afterComponents, borrowed, readPins, acceptedViews, managedReaction, Collections.emptyList());
    }

    SourceObservationProgram(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
                             ExternalEventCause externalCause, ClosureEnvironment environment,
                             ExecutionPolicy executionPolicy, List<SourceState> predecessors,
                             List<SourceState> results, Set<DocumentId> owned, List<Step> steps,
                             List<ReferenceProjection> projections, List<ManagedOccurrenceBinding> beforeBindings,
                             List<ManagedOccurrenceBinding> afterBindings, List<ComponentSnapshot> beforeComponents,
                             List<ComponentSnapshot> afterComponents, List<SourceObservationProgram> borrowed,
                             List<ManagedReadPin> readPins, List<AcceptedAttachmentView> acceptedViews,
                             ManagedReactionContext managedReaction, List<AcceptedInitializationInstallation> acceptedInitializations) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy,
                predecessors, results, owned, steps, projections, beforeBindings, afterBindings, beforeComponents,
                afterComponents, borrowed, readPins, acceptedViews, managedReaction, acceptedInitializations, Collections.emptyList());
    }

    SourceObservationProgram(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
                             ExternalEventCause externalCause, ClosureEnvironment environment,
                             ExecutionPolicy executionPolicy, List<SourceState> predecessors,
                             List<SourceState> results, Set<DocumentId> owned, List<Step> steps,
                             List<ReferenceProjection> projections, List<ManagedOccurrenceBinding> beforeBindings,
                             List<ManagedOccurrenceBinding> afterBindings, List<ComponentSnapshot> beforeComponents,
                             List<ComponentSnapshot> afterComponents, List<SourceObservationProgram> borrowed,
                             List<ManagedReadPin> readPins, List<AcceptedAttachmentView> acceptedViews,
                             ManagedReactionContext managedReaction, List<AcceptedInitializationInstallation> acceptedInitializations,
                             List<SkippedWork> skippedWork) {
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(invocationIdentity, "source invocation");
        this.causeKind = Objects.requireNonNull(causeKind, "causeKind");
        this.causeIdentity = ClosureValueSupport.requireSha256Identity(causeIdentity, "source cause");
        this.externalCause = externalCause;
        if ((causeKind == ProcessingCause.Kind.EXTERNAL) != (externalCause != null)
                || externalCause != null && !causeIdentity.equals(externalCause.causeIdentity())) {
            throw new IllegalArgumentException("Source cause descriptor mismatch");
        }
        this.environment = Objects.requireNonNull(environment, "environment");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
        this.sourcePredecessors = canonicalStates(predecessors, owned);
        this.sourceResults = canonicalStates(results, owned);
        this.ownedDocumentIds = Collections.unmodifiableSet(new TreeSet<DocumentId>(owned));
        if (this.ownedDocumentIds.isEmpty() || !stateIds(predecessors).containsAll(owned)
                || !stateIds(results).containsAll(owned)) {
            throw new IllegalArgumentException("Source ownership requires exact predecessor and result states");
        }
        for (SourceState state : predecessors) if (owned.contains(state.documentId()) && !state.hasResidentBody())
            throw new IllegalArgumentException("Owned source predecessor requires its exact body");
        for (SourceState state : results) if (owned.contains(state.documentId()) && !state.hasResidentBody())
            throw new IllegalArgumentException("Owned source result requires its exact body");
        this.steps = immutable(steps);
        this.skippedWork = immutable(skippedWork);
        Set<String> workIdentities = new TreeSet<>();
        Map<String, DocumentId> terminationRequests = new TreeMap<>();
        for (Step step : this.steps) {
            if (!workIdentities.add(step.workIdentity())) throw new IllegalArgumentException("Duplicate source work identity");
            for (Action action : step.actions()) if (action instanceof TerminationRequest)
                terminationRequests.put(step.workIdentity(), step.targetDocumentId());
        }
        for (SkippedWork skipped : this.skippedWork) {
            if (!owned.contains(skipped.targetDocumentId()) || !workIdentities.add(skipped.workIdentity())
                    || !skipped.targetDocumentId().equals(terminationRequests.get(skipped.requestWorkIdentity())))
                throw new IllegalArgumentException("Skipped work requires its unique owned termination request");
        }
        this.referenceProjections = immutable(projections);
        for (ReferenceProjection projection : projections) if (!owned.contains(projection.targetDocumentId()))
            throw new IllegalArgumentException("A program may retain only its owned reference projections");
        this.sourceBeforeBindings = immutable(beforeBindings);
        this.sourceAfterBindings = immutable(afterBindings);
        this.sourceBeforeComponents = immutable(beforeComponents);
        this.sourceAfterComponents = immutable(afterComponents);
        verifyTopology(beforeBindings, beforeComponents, predecessors, owned);
        verifyTopology(afterBindings, afterComponents, results, owned);
        this.borrowedPrograms = immutable(borrowed);
        this.sourceReadPins = immutable(readPins);
        this.managedReaction = managedReaction;
        if (managedReaction != null) {
            if (causeKind != ProcessingCause.Kind.EXTERNAL)
                throw new IllegalArgumentException("A managed reaction retains external source provenance");
            for (ManagedReactionContext.DueOccurrence due : managedReaction.dueOccurrences())
                if (!owned.contains(due.consumerLineage()))
                    throw new IllegalArgumentException("A source program may retain only its owned reaction placements");
        }
        this.acceptedViews = immutable(acceptedViews);
        Set<String> acceptedSites = new TreeSet<>();
        for (AcceptedAttachmentView view : this.acceptedViews) {
            if (!owned.contains(view.selection().creatorLineage()))
                throw new IllegalArgumentException("A program may retain only its owned attachment selections");
            if (!acceptedSites.add(view.creatorPatchSite() + ":" + view.selection().occurrenceIdentity()))
                throw new IllegalArgumentException("An attachment site must retain exactly one accepted view");
        }
        this.acceptedInitializations = immutable(acceptedInitializations);
        for (AcceptedInitializationInstallation installation : this.acceptedInitializations) {
            if (!owned.contains(installation.selection().creatorLineage()))
                throw new IllegalArgumentException("A program may retain only its owned initialization installations");
            if (!acceptedSites.add(installation.creatorPatchSite() + ":" + installation.selection().occurrenceIdentity()))
                throw new IllegalArgumentException("An attachment site must retain exactly one accepted view");
        }
        Set<String> borrowedOwners = new TreeSet<>(); Set<String> borrowedIds = new TreeSet<>();
        for (SourceObservationProgram dependency : borrowed) {
            if (invocationIdentity.equals(dependency.invocationIdentity())
                    || !Collections.disjoint(owned, dependency.ownedDocumentIds())
                    || !borrowedIds.add(dependency.invocationIdentity()))
                throw new IllegalArgumentException("Borrowed programs must be unique completed independent operations");
            for (DocumentId dependencyOwner : dependency.ownedDocumentIds())
                if (!borrowedOwners.add(borrowedOwnershipKey(dependency, dependencyOwner)))
                throw new IllegalArgumentException("Two borrowed operations own one source at this causal position");
        }
    }

    /** Canonical init0 and this origin's processing view are independent interpretation contexts. */
    static String borrowedOwnershipKey(SourceObservationProgram source, DocumentId owner) {
        return (source.causeKind() == ProcessingCause.Kind.ADMISSION ? "INITIALIZATION:" : "PROCESSING:") + owner.value();
    }

    public String invocationIdentity() { return invocationIdentity; }
    public ProcessingCause.Kind causeKind() { return causeKind; }
    public String causeIdentity() { return causeIdentity; }
    public ExternalEventCause externalCause() { return externalCause; }
    public ClosureEnvironment environment() { return environment; }
    public ExecutionPolicy executionPolicy() { return executionPolicy; }
    public List<SourceState> sourcePredecessors() { return sourcePredecessors; }
    public List<SourceState> sourceResults() { return sourceResults; }
    public Set<DocumentId> ownedDocumentIds() { return ownedDocumentIds; }
    public List<Step> steps() { return steps; }
    public List<SkippedWork> skippedWork() { return skippedWork; }
    public List<ReferenceProjection> referenceProjections() { return referenceProjections; }
    /** Exact owned source topology; never inferred from the observer's or latest host graph. */
    public List<ManagedOccurrenceBinding> sourceBeforeBindings() { return sourceBeforeBindings; }
    public List<ManagedOccurrenceBinding> sourceAfterBindings() { return sourceAfterBindings; }
    public List<ComponentSnapshot> sourceBeforeComponents() { return sourceBeforeComponents; }
    public List<ComponentSnapshot> sourceAfterComponents() { return sourceAfterComponents; }
    /** Shared authenticated DAG links; borrowed actions do not become this program's owned emissions. */
    public List<SourceObservationProgram> borrowedPrograms() { return borrowedPrograms; }
    /** Mandatory accepted placement views, not a copy of the producing host's optional cache.
     * Other exact external references remain in owned topology and may require a later exact read. */
    public List<ManagedReadPin> sourceReadPins() { return sourceReadPins; }
    /** Canonical activation cuts, retained even when source bodies at different sites are equal. */
    public List<AcceptedAttachmentView> acceptedViews() { return acceptedViews; }
    public List<AcceptedInitializationInstallation> acceptedInitializations() { return acceptedInitializations; }
    public java.util.Optional<ManagedReactionContext> managedReaction() { return java.util.Optional.ofNullable(managedReaction); }

    private static List<ManagedReadPin> retainedPins(List<AcceptedAttachmentView> acceptedViews,
                                                    List<AcceptedInitializationInstallation> acceptedInitializations) {
        java.util.Map<String, ManagedReadPin> pins = new java.util.TreeMap<>();
        for (AcceptedAttachmentView view : acceptedViews) {
            ManagedReadPin pin = view.selectedView();
            pins.put(pin.documentId().value() + "\u0000" + pin.blueId(), pin);
        }
        for (AcceptedInitializationInstallation installation : acceptedInitializations) {
            ManagedReadPin pin = installation.selectedView();
            pins.put(pin.documentId().value() + "\u0000" + pin.blueId(), pin);
        }
        return new ArrayList<>(pins.values());
    }

    private static List<ManagedOccurrenceBinding> ownedBindings(List<ManagedOccurrenceBinding> bindings, Set<DocumentId> owned) {
        List<ManagedOccurrenceBinding> selected = new ArrayList<>();
        for (ManagedOccurrenceBinding binding : bindings) if (owned.contains(binding.sourceDocumentId())) selected.add(binding);
        return selected;
    }

    private static List<ComponentSnapshot> ownedComponents(List<ComponentSnapshot> components, Set<DocumentId> owned) {
        List<ComponentSnapshot> selected = new ArrayList<>();
        for (ComponentSnapshot component : components) {
            if (Collections.disjoint(component.orderedMemberDocumentIds(), owned)) continue;
            if (!owned.containsAll(component.orderedMemberDocumentIds()))
                throw new IllegalArgumentException("Source program cannot split an atomic component");
            selected.add(component);
        }
        return selected;
    }

    private static void verifyTopology(List<ManagedOccurrenceBinding> bindings, List<ComponentSnapshot> components,
                                       List<SourceState> states, Set<DocumentId> owned) {
        java.util.Map<DocumentId, SourceState> byId = new java.util.HashMap<>();
        for (SourceState state : states) byId.put(state.documentId(), state);
        for (ManagedOccurrenceBinding binding : bindings) {
            if (!owned.contains(binding.sourceDocumentId())
                    || owned.contains(binding.targetDocumentId()) && !byId.containsKey(binding.targetDocumentId()))
                throw new IllegalArgumentException("Retained topology lies outside source evidence");
            ManagedOccurrenceBinding.verified(binding.occurrenceIdentity(), binding.bindingIdentity(), binding.bindingPolicyIdentity(),
                    binding.sourceDocumentId(), binding.sourceAddress(), binding.targetDocumentId(), binding.expectedTargetBlueId(),
                    binding.active(), binding.pendingHistoricalEpoch());
            // External endpoints name exact selected values, not the latest mutable source cell.
            // Multiple aliases may select different historical values of one lineage; neither
            // reference asserts an epoch without a separately consumed source operation.
            if (binding.active() || binding.pendingHistoricalEpoch() != null) {
                SourceState source = byId.get(binding.sourceDocumentId());
                FrozenNode selected = source.frozenDocument().at(binding.sourcePath());
                if (selected == null || !selected.isReferenceOnly()
                        || !binding.expectedTargetBlueId().equals(selected.getReferenceBlueId()))
                    throw new IllegalArgumentException("Retained source occurrence disagrees with its exact owned reference");
            }
        }
        Set<DocumentId> covered = new TreeSet<>();
        for (ComponentSnapshot component : components) {
            for (int i = 0; i < component.orderedMemberDocumentIds().size(); i++) {
                DocumentId member = component.orderedMemberDocumentIds().get(i);
                if (!owned.contains(member) || !covered.add(member) || !byId.get(member).blueId().equals(component.orderedMemberBlueIds().get(i)))
                    throw new IllegalArgumentException("Source component does not establish its exact owned state");
            }
        }
        // Package-private synthetic step fixtures may omit topology. Actual captures always have
        // complete component evidence; admission capabilities explicitly require it.
        if (!components.isEmpty() && !covered.equals(owned))
            throw new IllegalArgumentException("Retained source components omit owned members");
    }

    /** Semantic source state only: no host generation, publication role or resident resolver. */
    public static final class SourceState {
        private final DocumentId documentId;
        private final String blueId;
        private final long epoch;
        private final boolean initialized;
        private final FrozenNode document;
        SourceState(DocumentId id, String blueId, long epoch, boolean initialized, FrozenNode document) {
            this(id, blueId, epoch, initialized, Objects.requireNonNull(document, "document"), false);
        }
        private SourceState(DocumentId id, String blueId, long epoch, boolean initialized, FrozenNode document, boolean headerOnly) {
            this.documentId = Objects.requireNonNull(id, "documentId");
            this.blueId = ClosureValueSupport.requireBlueId(blueId, "source state");
            this.epoch = ClosureValueSupport.requireSafeInteger(epoch, "source epoch");
            this.initialized = initialized;
            this.document = document;
        }
        /** Package-private: the enclosing source result authenticates this consumed dependency boundary. */
        static SourceState headerOnly(DocumentId id, String blueId, long epoch, boolean initialized) {
            return new SourceState(id, blueId, epoch, initialized, null, true);
        }
        public DocumentId documentId() { return documentId; }
        public String blueId() { return blueId; }
        public long epoch() { return epoch; }
        public boolean initialized() { return initialized; }
        public boolean hasResidentBody() { return document != null; }
        public Node document() { return frozenDocument().toNode(); }
        public FrozenNode frozenDocument() {
            if (document == null) throw new blue.language.processor.ExecutionEvidenceUnavailableException(
                    "Exact retained dependency body is not resident", Collections.singletonList(blueId));
            return document;
        }
    }

    /** One entered handler/continuation, before all its nested work. */
    public static final class Step {
        private final String workIdentity;
        private final String entrySiteIdentity;
        private final DocumentId targetDocumentId;
        private final WorkKind kind;
        private final String channelKey;
        private final FrozenNode payload;
        private final FrozenNode before;
        private final FrozenNode after;
        private final List<FrozenNode> emittedEvents;
        private final List<FrozenJsonPatch> orderedPatches;
        private final boolean identityAffecting;
        private final List<Action> actions;

        Step(DocumentStepInput input, LocalDocumentStepResult result, List<Action> actions) {
            this(input, result, actions, ClosureIdentityService.INSTANCE.observationEntrySiteIdentity(input.work().workIdentity()));
        }

        Step(DocumentStepInput input, LocalDocumentStepResult result, List<Action> actions, String entrySiteIdentity) {
            this(input.work().workIdentity(), entrySiteIdentity, input.work().targetDocumentId(), input.work().kind(), input.work().channelKey(),
                    FrozenNode.fromResolvedNode(input.exactPayload()),
                    FrozenNode.fromResolvedNode(input.targetDocument().document()),
                    FrozenNode.fromResolvedNode(result.resultingBody()),
                    freeze(result.emittedEvents()), result.orderedPatches(), result.identityAffecting(), actions);
        }

        Step(String workIdentity, String entrySiteIdentity, DocumentId target, WorkKind kind, String channelKey, FrozenNode payload, FrozenNode before,
             FrozenNode after, List<FrozenNode> events, List<FrozenJsonPatch> patches,
             boolean identityAffecting, List<Action> actions) {
            this.workIdentity = ClosureValueSupport.requireSha256Identity(workIdentity, "workIdentity");
            this.entrySiteIdentity = ClosureValueSupport.requireSha256Identity(entrySiteIdentity, "entrySiteIdentity");
            this.targetDocumentId = Objects.requireNonNull(target, "target");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
            this.payload = Objects.requireNonNull(payload, "payload");
            this.before = Objects.requireNonNull(before, "before");
            this.after = Objects.requireNonNull(after, "after");
            this.emittedEvents = immutable(events);
            this.orderedPatches = immutable(patches);
            this.identityAffecting = identityAffecting;
            this.actions = immutable(actions);
            for (Action action : actions) if (action instanceof Patch) {
                List<ManagedOccurrenceBinding> rows = ((Patch) action).resultingBindings();
                if (rows != null) for (ManagedOccurrenceBinding row : rows)
                    if (!target.equals(row.sourceDocumentId()))
                        throw new IllegalArgumentException("A source patch retains only its own outgoing rows");
            }
        }

        public DocumentId targetDocumentId() { return targetDocumentId; }
        /** Original owning work, never reconstructed from an aggregate/group settlement or Entry hash. */
        public String workIdentity() { return workIdentity; }
        public String entrySiteIdentity() { return entrySiteIdentity; }
        public WorkKind kind() { return kind; }
        public String channelKey() { return channelKey; }
        public Node exactPayload() { return payload.toNode(); }
        public Node beforeBody() { return before.toNode(); }
        public Node resultingBody() { return after.toNode(); }
        public List<Node> emittedEvents() {
            List<Node> nodes = new ArrayList<Node>();
            for (FrozenNode event : emittedEvents) nodes.add(event.toNode());
            return Collections.unmodifiableList(nodes);
        }
        public List<FrozenJsonPatch> orderedPatches() { return orderedPatches; }
        public boolean identityAffecting() { return identityAffecting; }
        public List<Action> actions() { return actions; }
        FrozenNode frozenPayload() { return payload; }
        FrozenNode frozenBefore() { return before; }
        FrozenNode frozenAfter() { return after; }
        List<FrozenNode> frozenEvents() { return emittedEvents; }
    }

    /** Original accepted identity, never an executed step or authority to bypass a live handler. */
    public static final class SkippedWork {
        private final String workIdentity, channelKey, sourceOccurrenceIdentity, requestWorkIdentity;
        private final DocumentId targetDocumentId;
        private final WorkKind kind;

        SkippedWork(ClosureWorkOccurrence work, String requestWorkIdentity) {
            this(work.workIdentity(), work.targetDocumentId(), work.kind(), work.channelKey(),
                    work.sourceOccurrenceIdentity(), requestWorkIdentity);
        }

        SkippedWork(String workIdentity, DocumentId target, WorkKind kind, String channel,
                String sourceOccurrenceIdentity, String requestWorkIdentity) {
            this.workIdentity = ClosureValueSupport.requireSha256Identity(workIdentity, "workIdentity");
            this.targetDocumentId = Objects.requireNonNull(target, "target");
            this.kind = Objects.requireNonNull(kind, "kind");
            if (kind == WorkKind.LIFECYCLE || kind == WorkKind.INITIALIZATION)
                throw new IllegalArgumentException("Lifecycle work cannot be suppressed as ordinary delivery");
            this.channelKey = Objects.requireNonNull(channel, "channel");
            this.sourceOccurrenceIdentity = ClosureValueSupport.requireSha256Identity(sourceOccurrenceIdentity, "sourceOccurrenceIdentity");
            this.requestWorkIdentity = ClosureValueSupport.requireSha256Identity(requestWorkIdentity, "requestWorkIdentity");
        }

        public String workIdentity() { return workIdentity; }
        public DocumentId targetDocumentId() { return targetDocumentId; }
        public WorkKind kind() { return kind; }
        public String channelKey() { return channelKey; }
        public String sourceOccurrenceIdentity() { return sourceOccurrenceIdentity; }
        public String requestWorkIdentity() { return requestWorkIdentity; }
    }

    /** A boundary in one step's declared action order. */
    public interface Action { }

    /** Exact termination request at its original position in one step's ordered effects. */
    public static final class TerminationRequest implements Action {
        private final String cause;
        private final String reason;

        TerminationRequest(String cause, String reason) {
            this.cause = ClosureValueSupport.requireNonEmptyText(cause, "terminationCause");
            this.reason = reason;
        }

        public String cause() { return cause; }
        public String reason() { return reason; }
    }

    /** A reference-only owned ancestor advance at the original source's Entry or Patch site. */
    public static final class ReferenceProjection {
        private final String siteIdentity, beforeBlueId, afterBlueId;
        private final DocumentId sourceDocumentId, targetDocumentId;
        private final FrozenNode beforeBody, afterBody;
        private final List<ReferenceChange> changes;

        ReferenceProjection(String siteIdentity, DocumentId sourceDocumentId, DocumentId targetDocumentId,
                String beforeBlueId, String afterBlueId, FrozenNode beforeBody, FrozenNode afterBody,
                List<ReferenceChange> changes) {
            this.siteIdentity = ClosureValueSupport.requireSha256Identity(siteIdentity, "projectionSite");
            this.sourceDocumentId = Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
            this.targetDocumentId = Objects.requireNonNull(targetDocumentId, "targetDocumentId");
            this.beforeBlueId = ClosureValueSupport.requireBlueId(beforeBlueId, "beforeBlueId");
            this.afterBlueId = ClosureValueSupport.requireBlueId(afterBlueId, "afterBlueId");
            this.beforeBody = Objects.requireNonNull(beforeBody, "beforeBody");
            this.afterBody = Objects.requireNonNull(afterBody, "afterBody");
            this.changes = immutable(changes);
            if (changes.isEmpty()) throw new IllegalArgumentException("Reference projection requires changed reference paths");
            String previous = null;
            for (ReferenceChange change : changes) {
                if (previous != null && ClosureValueSupport.comparePortableText(previous, change.path()) >= 0)
                    throw new IllegalArgumentException("Reference projection paths must be unique and canonically ordered");
                previous = change.path();
            }
        }
        public String siteIdentity() { return siteIdentity; }
        public DocumentId sourceDocumentId() { return sourceDocumentId; }
        public DocumentId targetDocumentId() { return targetDocumentId; }
        public String beforeBlueId() { return beforeBlueId; }
        public String afterBlueId() { return afterBlueId; }
        public FrozenNode beforeBody() { return beforeBody; }
        public FrozenNode afterBody() { return afterBody; }
        public List<ReferenceChange> changes() { return changes; }
    }

    public static final class ReferenceChange {
        private final String path;
        private final FrozenNode before, after;
        ReferenceChange(String path, FrozenNode before, FrozenNode after) {
            this.path = ClosureValueSupport.requireAbsolutePointer(path, "referencePath");
            this.before = Objects.requireNonNull(before, "beforeReference");
            this.after = Objects.requireNonNull(after, "afterReference");
            if ("/".equals(path) || !before.isReferenceOnly() || !after.isReferenceOnly()
                    || Objects.equals(before.getReferenceBlueId(), after.getReferenceBlueId()))
                throw new IllegalArgumentException("Projection changes must replace a non-Root pure reference");
        }
        public String path() { return path; }
        public FrozenNode before() { return before; }
        public FrozenNode after() { return after; }
    }

    /** Exact post-patch view before synchronous Document Update callbacks. */
    public static final class Patch implements Action {
        private final String siteIdentity;
        private final String transitionIdentity;
        private final FrozenNode document;
        private final FrozenJsonPatch patch;
        private final List<DocumentUpdateOccurrence> updates;
        private final List<ManagedOccurrenceBinding> resultingBindings;

        Patch(Node document, FrozenJsonPatch patch, List<DocumentUpdateOccurrence> updates) {
            this(null, document, patch, updates);
        }

        Patch(FrozenNode document, FrozenJsonPatch patch, List<DocumentUpdateOccurrence> updates) {
            this(null, document, patch, updates);
        }

        Patch(String siteIdentity, Node document, FrozenJsonPatch patch, List<DocumentUpdateOccurrence> updates) {
            this(siteIdentity, null, document, patch, updates);
        }

        Patch(String siteIdentity, FrozenNode document, FrozenJsonPatch patch, List<DocumentUpdateOccurrence> updates) {
            this(siteIdentity, null, document, patch, updates);
        }

        Patch(String siteIdentity, String transitionIdentity, Node document, FrozenJsonPatch patch, List<DocumentUpdateOccurrence> updates) {
            this(siteIdentity, transitionIdentity, FrozenNode.fromResolvedNode(Objects.requireNonNull(document, "document")), patch, updates);
        }

        Patch(String siteIdentity, String transitionIdentity, FrozenNode document, FrozenJsonPatch patch, List<DocumentUpdateOccurrence> updates) {
            this(siteIdentity, transitionIdentity, document, patch, updates, null);
        }

        private Patch(String siteIdentity, String transitionIdentity, FrozenNode document, FrozenJsonPatch patch,
                      List<DocumentUpdateOccurrence> updates, List<ManagedOccurrenceBinding> resultingBindings) {
            this.siteIdentity = siteIdentity == null ? null : ClosureValueSupport.requireSha256Identity(siteIdentity, "siteIdentity");
            this.transitionIdentity = transitionIdentity == null ? null : ClosureValueSupport.requireSha256Identity(transitionIdentity, "transitionIdentity");
            this.document = Objects.requireNonNull(document, "document");
            this.patch = Objects.requireNonNull(patch, "patch");
            this.updates = immutable(updates);
            if (resultingBindings == null) this.resultingBindings = null;
            else {
                List<ManagedOccurrenceBinding> ordered = new ArrayList<>(resultingBindings);
                Collections.sort(ordered);
                Set<String> occurrences = new TreeSet<>(), paths = new TreeSet<>();
                for (ManagedOccurrenceBinding binding : ordered) {
                    Objects.requireNonNull(binding, "resultingBinding");
                    if (!occurrences.add(binding.occurrenceIdentity())
                            || !paths.add(binding.sourceDocumentId().value() + "\u0000" + binding.sourcePath()))
                        throw new IllegalArgumentException("Repeated outgoing occurrence at one source patch");
                }
                this.resultingBindings = immutable(ordered);
            }
        }

        Patch withResultingBindings(List<ManagedOccurrenceBinding> bindings) {
            return new Patch(siteIdentity, transitionIdentity, document, patch, updates,
                    Objects.requireNonNull(bindings, "resultingBindings"));
        }

        public Node document() { return document.toNode(); }
        public String siteIdentity() { return siteIdentity; }
        /** Original synchronous update cause. Null only in non-exportable synthetic recorder fixtures. */
        public String transitionIdentity() { return transitionIdentity; }
        public FrozenJsonPatch patch() { return patch; }
        public List<DocumentUpdateOccurrence> updates() { return updates; }
        /**
         * Complete outgoing rows of this step's document after surface reconciliation, before
         * canonical component-reference remapping or callbacks. Other owners' rows are excluded.
         * Null only while recording an incomplete patch or in non-exportable synthetic fixtures.
         */
        public List<ManagedOccurrenceBinding> resultingBindings() { return resultingBindings; }
        FrozenNode frozenDocument() { return document; }
    }

    /** Exact event occurrence at its original FIFO admission site. */
    public static final class Enqueue implements Action {
        private final String contractKey;
        private final FrozenNode event;
        private final String eventBlueId;
        private final String occurrenceIdentity;

        Enqueue(String contractKey, Node event, String eventBlueId, String occurrenceIdentity) {
            this(contractKey, FrozenNode.fromResolvedNode(Objects.requireNonNull(event, "event")), eventBlueId, occurrenceIdentity);
        }

        Enqueue(String contractKey, FrozenNode event, String eventBlueId, String occurrenceIdentity) {
            this.contractKey = Objects.requireNonNull(contractKey, "contractKey");
            this.event = Objects.requireNonNull(event, "event");
            this.eventBlueId = Objects.requireNonNull(eventBlueId, "eventBlueId");
            this.occurrenceIdentity = Objects.requireNonNull(occurrenceIdentity, "occurrenceIdentity");
        }

        public String contractKey() { return contractKey; }
        public Node event() { return event.toNode(); }
        public String eventBlueId() { return eventBlueId; }
        public String occurrenceIdentity() { return occurrenceIdentity; }
        FrozenNode frozenEvent() { return event; }
    }

    private static Set<DocumentId> ids(ClosureInvocationInput input) {
        Set<DocumentId> ids = new TreeSet<DocumentId>();
        for (ManagedDocumentSnapshot state : input.snapshot().managedDocuments()) ids.add(state.documentId());
        return ids;
    }
    private static Set<DocumentId> stateIds(List<SourceState> states) {
        Set<DocumentId> ids = new TreeSet<DocumentId>();
        for (SourceState state : states) if (!ids.add(state.documentId()))
            throw new IllegalArgumentException("Duplicate source state");
        return ids;
    }
    private static List<SourceState> canonicalStates(List<SourceState> states, Set<DocumentId> owned) {
        List<SourceState> result = new ArrayList<>();
        for (SourceState state : states) result.add(owned.contains(state.documentId()) ? state
                : SourceState.headerOnly(state.documentId(), state.blueId(), state.epoch(), state.initialized()));
        return immutable(result);
    }
    private static List<SourceState> before(ClosureInvocationInput input, Set<DocumentId> owned) {
        List<SourceState> values = new ArrayList<SourceState>();
        for (ManagedDocumentSnapshot state : input.snapshot().managedDocuments()) if (owned.contains(state.documentId()))
            values.add(new SourceState(state.documentId(), state.blueId(), state.epoch(), state.initialized(), FrozenNode.fromResolvedNode(state.document())));
        return values;
    }
    private static List<SourceState> after(ClosureProcessResult result, Set<DocumentId> owned) {
        List<SourceState> values = new ArrayList<SourceState>();
        for (ResultingDocument state : result.resultingDocuments()) if (owned.contains(state.documentId()))
            values.add(new SourceState(state.documentId(), state.afterBlueId(), state.epoch(), state.initialized(), FrozenNode.fromResolvedNode(state.document())));
        return values;
    }
    private static List<FrozenNode> freeze(List<Node> nodes) {
        List<FrozenNode> result = new ArrayList<FrozenNode>();
        for (Node node : nodes) result.add(FrozenNode.fromResolvedNode(node));
        return result;
    }

    private static <T> List<T> immutable(List<T> values) {
        ArrayList<T> copy = new ArrayList<T>(Objects.requireNonNull(values, "values"));
        for (T value : copy) Objects.requireNonNull(value, BlueLanguageConstants.OBJECT_VALUE);
        return Collections.unmodifiableList(copy);
    }
}
