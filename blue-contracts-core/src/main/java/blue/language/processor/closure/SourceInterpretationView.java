package blue.language.processor.closure;

import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.provider.CyclicSetProof;
import java.util.*;

/**
 * Invocation-private interpretation state of one authenticated canonical initialization.
 * It is not a mutable lineage cell, does not run a handler, and owns no gas meter. In particular,
 * constructing or advancing this view cannot rewind a later authoritative source in the driver.
 * External references are immutable exact values, not today's mutable external graph vertices.
 */
final class SourceInterpretationView {
    private final SourceObservationProgram program;
    private final List<SourceObservationProgram.Step> steps;
    private final Set<DocumentId> owned;
    private final Map<DocumentId, Node> bodies = new LinkedHashMap<>();
    private final Map<DocumentId, Long> generations = new LinkedHashMap<>();
    private final ManagedDocumentGraph originalInternalGraph;
    private final ComponentFinalizationKernel finalizer = new ComponentFinalizationKernel();
    private final Deque<Frame> frames = new ArrayDeque<>();
    private List<ManagedOccurrenceBinding> bindings;
    private ComponentFinalizationResult finalized;
    private int nextStep;
    private final Set<SourceObservationProgram.ReferenceProjection> projected =
            Collections.newSetFromMap(new IdentityHashMap<SourceObservationProgram.ReferenceProjection, Boolean>());
    private boolean completed;

    static SourceInterpretationView from(SourceInitialization initialization) {
        return new SourceInterpretationView(Objects.requireNonNull(initialization).program());
    }

    private SourceInterpretationView(SourceObservationProgram program) {
        this.program = program;
        this.owned = program.ownedDocumentIds();
        List<SourceObservationProgram.Step> sourceSteps = new ArrayList<>();
        for (SourceObservationProgram.Step step : program.steps())
            if (owned.contains(step.targetDocumentId())) sourceSteps.add(step);
        this.steps = Collections.unmodifiableList(sourceSteps);
        for (ComponentSnapshot component : program.sourceBeforeComponents())
            for (DocumentId member : component.orderedMemberDocumentIds()) generations.put(member, component.componentGeneration());
        for (SourceObservationProgram.SourceState state : program.sourcePredecessors())
            if (owned.contains(state.documentId())) bodies.put(state.documentId(), state.document());
        bindings = new ArrayList<>(program.sourceBeforeBindings());
        originalInternalGraph = ManagedDocumentGraph.fromBindings(owned, internalBindings(bindings));
        finalizeView();
        for (SourceObservationProgram.SourceState state : program.sourcePredecessors())
            if (owned.contains(state.documentId())) requireState(state);
    }

    SourceObservationProgram program() { return program; }
    List<SourceObservationProgram.Step> steps() { return steps; }
    Set<DocumentId> ownedDocumentIds() { return owned; }
    Node document(DocumentId member) { return requireOwned(member).document(); }
    String blueId(DocumentId member) { return requireOwned(member).blueId(); }
    List<ManagedOccurrenceBinding> bindings() { return Collections.unmodifiableList(bindings); }
    List<FinalizedComponentEvidence> components() { return finalized.components(); }
    AffectedClosureSnapshot snapshot() {
        List<ManagedDocumentSnapshot> states = new ArrayList<>();
        for (FinalizedDocumentEvidence state : finalized.documents().values()) states.add(new ManagedDocumentSnapshot(
                state.documentId(), state.blueId(), state.document(), completed, false, false, 0L, state.componentGeneration()));
        List<ComponentSnapshot> components = new ArrayList<>();
        for (FinalizedComponentEvidence component : finalized.components()) components.add(component.component());
        return ClosureEvidenceFactory.affectedClosure(0L, states, finalized.finalizedGraph().bindings(), components,
                Collections.emptyList());
    }

    /** FIFO/continuation selection remains in the driver; only its exact retained next step enters. */
    void enterStep(SourceObservationProgram.Step step) {
        if (completed || nextStep >= steps.size() || steps.get(nextStep) != step
                || !owned.contains(step.targetDocumentId()))
            throw new IllegalArgumentException("Initialization interpretation has a different original step");
        if (!same(document(step.targetDocumentId()), step.beforeBody()))
            throw new IllegalArgumentException("Initialization step does not start at its original exact source view");
        nextStep++;
        frames.addLast(new Frame(step));
    }

    /** Consumes original actions in order, without running their producing application again. */
    void consumeAction(SourceObservationProgram.Action action) {
        consumeAction(action, bindings);
    }

    /** One source patch and its owning reconciler's exact rows share one proof boundary. */
    void consumeAction(SourceObservationProgram.Action action, Collection<ManagedOccurrenceBinding> exactRowsAfterPatch) {
        Frame frame = active();
        if (frame.nextAction >= frame.step.actions().size() || frame.step.actions().get(frame.nextAction) != action)
            throw new IllegalArgumentException("Initialization action differs from the authenticated continuation");
        frame.nextAction++;
        if (action instanceof SourceObservationProgram.Patch) {
            bindings = checkedBindings(exactRowsAfterPatch);
            bodies.put(frame.step.targetDocumentId(), ((SourceObservationProgram.Patch) action).document());
            finalizeView();
        }
    }

    /** The owning surface reconciler may update only this interpretation's original source rows. */
    void reconcileBindings(Collection<ManagedOccurrenceBinding> exactSourceRows) {
        if (completed) throw new IllegalStateException("Initialization interpretation already completed");
        bindings = checkedBindings(exactSourceRows); finalizeView();
    }

    private List<ManagedOccurrenceBinding> checkedBindings(Collection<ManagedOccurrenceBinding> exactSourceRows) {
        List<ManagedOccurrenceBinding> checked = new ArrayList<>();
        for (ManagedOccurrenceBinding binding : Objects.requireNonNull(exactSourceRows)) {
            if (!owned.contains(binding.sourceDocumentId())) throw new IllegalArgumentException("Foreign source row in initialization view");
            ManagedOccurrenceBinding.verified(binding.occurrenceIdentity(), binding.bindingIdentity(), binding.bindingPolicyIdentity(),
                    binding.sourceDocumentId(), binding.sourceAddress(), binding.targetDocumentId(), binding.expectedTargetBlueId(),
                    binding.active(), binding.pendingHistoricalEpoch());
            checked.add(binding);
        }
        Collections.sort(checked); return checked;
    }

    void finishStep(SourceObservationProgram.Step step) {
        Frame frame = active();
        if (frame.step != step || frame.nextAction != step.actions().size())
            throw new IllegalArgumentException("Initialization step finished with a different or incomplete continuation");
        // Runtime completion can carry processor-only canonicalization after its final application
        // patch. This exact result was retained by the producing runtime, not supplied by a host.
        bodies.put(step.targetDocumentId(), step.resultingBody());
        finalizeView();
        frames.removeLast();
    }

    /** Imports only the producing observer's retained reference-only projection at its exact site. */
    void projectReference(SourceObservationProgram.ReferenceProjection projection) {
        boolean retained = false;
        for (SourceObservationProgram.ReferenceProjection candidate : program.referenceProjections())
            if (candidate == projection) retained = true;
        if (!retained || !owned.contains(projection.targetDocumentId()) || !projected.add(projection))
            throw new IllegalArgumentException("Reference projection is not an unconsumed original initialization projection");
        DocumentId target = projection.targetDocumentId();
        if (!blueId(target).equals(projection.beforeBlueId()) || !same(document(target), projection.beforeBody().toNode()))
            throw new IllegalArgumentException("Initialization reference projection starts at another exact source view");
        Node proposed = document(target);
        List<ManagedOccurrenceBinding> updated = new ArrayList<>(bindings);
        for (SourceObservationProgram.ReferenceChange change : projection.changes()) {
            Node current = NodePathEditor.getOrNull(proposed, change.path());
            if (current == null || !same(current, change.before().toNode()))
                throw new IllegalArgumentException("Initialization projection's exact old reference does not match");
            NodePathEditor.put(proposed, change.path(), change.after().toNode());
            for (int index = 0; index < updated.size(); index++) {
                ManagedOccurrenceBinding binding = updated.get(index);
                if (binding.sourceDocumentId().equals(target) && binding.sourcePath().equals(change.path()))
                    updated.set(index, ManagedOccurrenceBinding.derived(binding.bindingPolicyIdentity(), target,
                            binding.sourceAddress(), binding.targetDocumentId(), change.after().getReferenceBlueId(),
                            binding.active(), binding.pendingHistoricalEpoch()));
            }
        }
        if (!same(proposed, projection.afterBody().toNode()))
            throw new IllegalArgumentException("Initialization projection changes application data beyond exact references");
        bindings = checkedBindings(updated); bodies.put(target, proposed); finalizeView();
        if (!blueId(target).equals(projection.afterBlueId()))
            throw new IllegalArgumentException("Initialization projection result has another exact identity");
    }

    /** Processor marker installation has its own completion site; it is not an application update. */
    void complete() {
        if (completed || !frames.isEmpty() || nextStep != steps.size())
            throw new IllegalStateException("Initialization interpretation is not at its completion boundary");
        for (SourceObservationProgram.SourceState result : program.sourceResults()) {
            if (!owned.contains(result.documentId())) continue;
            Node marker = NodePathEditor.getOrNull(result.document(), ProcessorPointerConstants.RELATIVE_INITIALIZED);
            if (marker == null) throw new IllegalArgumentException("Canonical initialization result has no processor marker");
            NodePathEditor.put(bodies.get(result.documentId()), ProcessorPointerConstants.RELATIVE_INITIALIZED, marker.clone());
        }
        bindings = new ArrayList<>(program.sourceAfterBindings());
        finalizeView();
        for (SourceObservationProgram.SourceState result : program.sourceResults())
            if (owned.contains(result.documentId())) requireState(result);
        completed = true;
    }

    ManagedReadPin selectedPin(DocumentId member) {
        FinalizedDocumentEvidence exact = requireOwned(member);
        CyclicSetProof proof = null;
        for (FinalizedComponentEvidence component : finalized.components())
            if (component.component().orderedMemberDocumentIds().contains(member)) {
                proof = component.component().completeCyclicProof(); break;
            }
        return ManagedReadPin.fromExactEvidence(member, exact.blueId(), exact.document(), proof);
    }

    private void finalizeView() {
        // External references are already selected exact values in the retained source bodies.
        // Do not import a newer external component or any reverse observer's mutable graph here.
        for (ManagedOccurrenceBinding binding : bindings) {
            if (!binding.active() || owned.contains(binding.targetDocumentId())) continue;
            Node value = NodePathEditor.getOrNull(bodies.get(binding.sourceDocumentId()), binding.sourcePath());
            if (value == null || !value.isReferenceOnly() || !binding.expectedTargetBlueId().equals(value.getBlueId()))
                throw new IllegalArgumentException("Initialization external reference lost its selected exact source binding");
        }
        finalized = finalizer.finalizeComponents(new ComponentFinalizationInput(originalInternalGraph, generations,
                bodies, internalBindings(bindings)));
        bodies.clear();
        for (FinalizedDocumentEvidence state : finalized.documents().values()) bodies.put(state.documentId(), state.document());
    }

    private List<ManagedOccurrenceBinding> internalBindings(Collection<ManagedOccurrenceBinding> values) {
        List<ManagedOccurrenceBinding> result = new ArrayList<>();
        for (ManagedOccurrenceBinding binding : values) if (owned.contains(binding.targetDocumentId())) result.add(binding);
        return result;
    }
    private FinalizedDocumentEvidence requireOwned(DocumentId member) {
        if (!owned.contains(member)) throw new IllegalArgumentException("Foreign initialization interpretation member");
        return finalized.document(member);
    }
    private void requireState(SourceObservationProgram.SourceState state) {
        if (!state.blueId().equals(blueId(state.documentId())) || !same(state.document(), document(state.documentId())))
            throw new IllegalArgumentException("Initialization interpretation differs from its authenticated source state");
    }
    private Frame active() {
        if (frames.isEmpty()) throw new IllegalStateException("Initialization has no active original step");
        return frames.getLast();
    }
    private static boolean same(Node left, Node right) { return NodeWireForm.get(left).equals(NodeWireForm.get(right)); }
    private static final class Frame {
        final SourceObservationProgram.Step step; int nextAction;
        Frame(SourceObservationProgram.Step step) { this.step = step; }
    }
}
