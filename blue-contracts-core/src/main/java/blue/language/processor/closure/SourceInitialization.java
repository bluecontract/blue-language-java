package blue.language.processor.closure;

import blue.language.processor.util.ProcessorContractConstants;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A canonical source initialization selected for synchronous observation at an installation site.
 *
 * <p>This validates the shape of an already authenticated successful source program, not proof of
 * its execution. Its association with the complete successful producing result, original atomic
 * ownership and source-local admission basis must come from the owning library or authenticated
 * receipt. A caller-supplied digest beside arbitrary program bytes does not grant that authority.
 * The ordinary source-program codec is also this capability's persistence format.</p>
 *
 * <p>The consumer starts from the authored dependency view and interprets the retained actions;
 * it must not eagerly replace its reference with the final initialized state. Source business work
 * is not executed or charged again. The consumer's own observation work is charged normally.</p>
 */
public final class SourceInitialization {
    private final SourceObservationProgram program;
    private final Map<DocumentId, SourceObservationProgram.SourceState> origins;

    private SourceInitialization(SourceObservationProgram program) {
        this.program = Objects.requireNonNull(program, "program");
        if (program.causeKind() != ProcessingCause.Kind.ADMISSION || program.externalCause() != null) {
            throw new IllegalArgumentException("Source initialization requires a successful admission program");
        }
        for (SourceObservationProgram.Step step : program.steps())
            for (SourceObservationProgram.Action action : step.actions())
                if (action instanceof SourceObservationProgram.TerminationRequest)
                    throw new IllegalArgumentException("A usable source initialization cannot request termination");
        if (!canonicalAdmissionCause(program.ownedDocumentIds()).causeIdentity().equals(program.causeIdentity()))
            throw new IllegalArgumentException("Source initialization requires its canonical source-local component admission cause");
        Map<DocumentId, SourceObservationProgram.SourceState> before = index(program.sourcePredecessors());
        Map<DocumentId, SourceObservationProgram.SourceState> after = index(program.sourceResults());
        verifySourceTopology(program.sourceBeforeBindings(), program.sourceBeforeComponents(), before);
        verifySourceTopology(program.sourceAfterBindings(), program.sourceAfterComponents(), after);
        Map<DocumentId, SourceObservationProgram.SourceState> selected = new LinkedHashMap<DocumentId, SourceObservationProgram.SourceState>();
        for (DocumentId owner : program.ownedDocumentIds()) {
            SourceObservationProgram.SourceState origin = before.get(owner);
            SourceObservationProgram.SourceState result = after.get(owner);
            if (origin == null || result == null || origin.initialized() || origin.epoch() != 0L
                    || !owner.value().equals(origin.blueId())
                    || marker(origin.document(), ProcessorContractConstants.KEY_INITIALIZED) != null
                    || marker(origin.document(), ProcessorContractConstants.KEY_TERMINATED) != null) {
                throw new IllegalArgumentException("Source initialization must start at its exact authored epoch-zero origin");
            }
            Node initialized = result == null ? null : marker(result.document(), ProcessorContractConstants.KEY_INITIALIZED);
            if (result == null || !result.initialized() || result.epoch() != 0L
                    || initialized == null || initialized.getType() == null
                    || !RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER.equals(initialized.getType().getBlueId())
                    || marker(result.document(), ProcessorContractConstants.KEY_TERMINATED) != null) {
                throw new IllegalArgumentException("Source initialization requires every owned member's usable initialized epoch-zero result");
            }
            selected.put(owner, origin);
        }
        origins = Collections.unmodifiableMap(selected);
    }

    /** Narrows an authenticated completed admission program; this does not execute or publish it. */
    public static SourceInitialization fromProgram(SourceObservationProgram program) {
        return new SourceInitialization(program);
    }

    /**
     * Canonical source-local admission for one complete authored component. The requested
     * materialization member is not causal input: entering a cyclic component through either
     * member must produce the same initialization operation. Singleton identities are unchanged.
     * The caller supplies component membership verified by the owning graph boundary.
     */
    public static AdmissionCause canonicalAdmissionCause(Set<DocumentId> sourceComponent) {
        Objects.requireNonNull(sourceComponent, "sourceComponent");
        DocumentId anchor = null;
        for (DocumentId member : sourceComponent) {
            Objects.requireNonNull(member, "source component member");
            if (anchor == null || member.compareTo(anchor) < 0) anchor = member;
        }
        if (anchor == null) throw new IllegalArgumentException("Canonical initialization requires a nonempty source component");
        return ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                "canonical-source:" + anchor.value(), null, null, "FULL_HISTORY");
    }

    public SourceObservationProgram program() { return program; }

    /** Original producing ownership, not a component inferred from today's potentially newer graph. */
    public Set<DocumentId> ownedDocumentIds() { return program.ownedDocumentIds(); }

    /**
     * Verifies the authored installation view without rewriting any consumer value.
     *
     * <p>An authoritative source cell may be init0 or arbitrarily later. In that case an exact
     * authored read pin supplies the predecessor; current-head equality with init0 is not required.
     * Only this consumer's installation occurrences are checked: a read-only source's later
     * topology is not the original initialization component. The retained program's own before/after
     * topology, checked when constructing this capability, supplies that component evidence.</p>
     */
    public void verifyInstallationBasis(AffectedClosureSnapshot snapshot, Set<DocumentId> consumerOwned,
                                        ClosureEnvironment environment, ExecutionPolicy policy) {
        verifySourceBasis(snapshot, consumerOwned, environment, policy);
        boolean installation = false;
        for (ManagedOccurrenceBinding binding : snapshot.occurrences()) {
            if (!consumerOwned.contains(binding.sourceDocumentId()) || !origins.containsKey(binding.targetDocumentId())) continue;
            if (!binding.active() && !Long.valueOf(-1L).equals(binding.pendingHistoricalEpoch())) continue;
            verifyAuthoredOccurrence(snapshot, binding, environment);
            installation = true;
        }
        if (!installation) throw new IllegalArgumentException("Source initialization has no selected authored consumer occurrence");
    }

    /**
     * Checks immutable source/environment evidence before an external handler creates a placement.
     * This neither activates a placement nor authorizes replay: the runtime must subsequently check
     * the actual installation occurrence at its canonical mutation site. An existing newer source
     * cell remains unchanged, and an exact authored pin supplies its independent origin.
     */
    public void verifySourceBasis(AffectedClosureSnapshot snapshot, Set<DocumentId> consumerOwned,
                                  ClosureEnvironment environment, ExecutionPolicy policy) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(consumerOwned, "consumerOwned");
        if (consumerOwned.isEmpty()) throw new IllegalArgumentException("Initialization observation requires a consumer owner");
        if (!SourceObservationProgramCodec.environment(program.environment()).equals(
                SourceObservationProgramCodec.environment(Objects.requireNonNull(environment, "environment")))
                || !SourceObservationProgramCodec.policy(program.executionPolicy()).equals(
                SourceObservationProgramCodec.policy(Objects.requireNonNull(policy, "policy")))) {
            throw new IllegalArgumentException("Source initialization belongs to another complete environment or execution policy");
        }
        for (DocumentId consumer : consumerOwned) {
            if (snapshot.managedDocument(consumer) == null || origins.containsKey(consumer)) {
                throw new IllegalArgumentException("Source initialization has missing or overlapping consumer ownership");
            }
        }
        for (SourceObservationProgram.SourceState origin : origins.values()) {
            ManagedDocumentSnapshot present = snapshot.managedDocument(origin.documentId());
            if (present == null) throw new IllegalArgumentException("Initialized source member is absent from the installation basis");
            Node selected;
            if (present.blueId().equals(origin.blueId())) {
                if (present.initialized() || present.terminated() || present.epoch() != 0L) {
                    throw new IllegalArgumentException("Authored source cell has inconsistent lifecycle or epoch");
                }
                selected = present.document();
            } else {
                ManagedReadPin pin = snapshot.readPin(origin.documentId(), origin.blueId());
                if (pin == null) throw new IllegalArgumentException("Source initialization requires its exact authored dependency pin");
                selected = pin.document();
            }
            // Cyclic member identities are established by their existing proof/pin authority, not
            // by incorrectly applying direct-content hashing to a cyclic member body here.
            if (!NodeWireForm.get(selected).equals(NodeWireForm.get(origin.document()))) {
                throw new IllegalArgumentException("Authored dependency differs from the authenticated source predecessor");
            }
        }
    }

    /**
     * Verifies only the newly installed occurrence, not earlier aliases selecting later source epochs.
     * The occurrence must already be active or pending at authored epoch -1 in the runtime's exact
     * post-mutation view. A merely prospective input row is not an accepted installation.
     */
    public void verifyInstallationOccurrence(AffectedClosureSnapshot snapshot, Set<DocumentId> consumerOwned,
                                             String occurrenceIdentity, ClosureEnvironment environment, ExecutionPolicy policy) {
        verifySourceBasis(snapshot, consumerOwned, environment, policy);
        String selected = ClosureValueSupport.requireSha256Identity(occurrenceIdentity, "occurrenceIdentity");
        for (ManagedOccurrenceBinding binding : snapshot.occurrences()) {
            if (!selected.equals(binding.occurrenceIdentity())) continue;
            if (!consumerOwned.contains(binding.sourceDocumentId()) || !origins.containsKey(binding.targetDocumentId())
                    || !binding.active() && !Long.valueOf(-1L).equals(binding.pendingHistoricalEpoch()))
                throw new IllegalArgumentException("Source initialization requires an accepted owned installation occurrence");
            verifyAuthoredOccurrence(snapshot, binding, environment);
            return;
        }
        throw new IllegalArgumentException("Selected initialization occurrence is absent");
    }

    private void verifyAuthoredOccurrence(AffectedClosureSnapshot snapshot, ManagedOccurrenceBinding binding,
                                          ClosureEnvironment environment) {
        SourceObservationProgram.SourceState origin = origins.get(binding.targetDocumentId());
        ManagedOccurrenceBinding.verified(binding.occurrenceIdentity(), binding.bindingIdentity(),
                binding.bindingPolicyIdentity(), binding.sourceDocumentId(), binding.sourceAddress(),
                binding.targetDocumentId(), binding.expectedTargetBlueId(), binding.active(), binding.pendingHistoricalEpoch());
        Node reference = NodePathEditor.getOrNull(snapshot.managedDocument(binding.sourceDocumentId()).document(), binding.sourcePath());
        if (!environment.managedBindingPolicyIdentity().equals(binding.bindingPolicyIdentity())
                || !origin.blueId().equals(binding.expectedTargetBlueId())
                || reference == null || !reference.isReferenceOnly()
                || !origin.blueId().equals(reference.getBlueId()))
            throw new IllegalArgumentException("Initialization occurrence must still select its exact authored source view");
    }

    private static Map<DocumentId, SourceObservationProgram.SourceState> index(List<SourceObservationProgram.SourceState> states) {
        Map<DocumentId, SourceObservationProgram.SourceState> result = new LinkedHashMap<DocumentId, SourceObservationProgram.SourceState>();
        for (SourceObservationProgram.SourceState state : states) {
            if (result.put(state.documentId(), state) != null) throw new IllegalArgumentException("Duplicate initialization source state");
        }
        return result;
    }

    private void verifySourceTopology(List<ManagedOccurrenceBinding> bindings, List<ComponentSnapshot> components,
                                      Map<DocumentId, SourceObservationProgram.SourceState> states) {
        if (components.isEmpty()) throw new IllegalArgumentException("Source initialization requires complete original component evidence");
        Set<DocumentId> owned = program.ownedDocumentIds();
        Set<DocumentId> covered = new HashSet<DocumentId>();
        Set<Set<DocumentId>> declaredPartition = new HashSet<Set<DocumentId>>();
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        for (ComponentSnapshot component : components) {
            Set<DocumentId> members = new HashSet<DocumentId>(component.orderedMemberDocumentIds());
            if (!owned.containsAll(members) || !declaredPartition.add(members)
                    || !identities.componentIdentity(component).equals(component.componentIdentity())
                    || !identities.componentStateIdentity(component).equals(component.componentStateIdentity())
                    || component.kind() == ComponentKind.CYCLIC
                    && !identities.cyclicProofIdentity(component).equals(component.cyclicProofIdentity())) {
                throw new IllegalArgumentException("Source initialization component ownership or identity is incomplete");
            }
            for (int index = 0; index < component.orderedMemberDocumentIds().size(); index++) {
                DocumentId member = component.orderedMemberDocumentIds().get(index);
                if (!covered.add(member) || states.get(member) == null
                        || !states.get(member).blueId().equals(component.orderedMemberBlueIds().get(index))) {
                    throw new IllegalArgumentException("Source initialization component does not establish its exact member state");
                }
            }
        }
        if (!covered.equals(owned)) throw new IllegalArgumentException("Source initialization components omit owned members");
        List<ManagedOccurrenceBinding> internal = new ArrayList<ManagedOccurrenceBinding>();
        Set<String> occurrenceIds = new HashSet<String>();
        for (ManagedOccurrenceBinding binding : bindings) {
            if (!owned.contains(binding.sourceDocumentId())
                    || owned.contains(binding.targetDocumentId()) && !states.containsKey(binding.targetDocumentId())
                    || !occurrenceIds.add(binding.occurrenceIdentity())
                    || !program.environment().managedBindingPolicyIdentity().equals(binding.bindingPolicyIdentity())) {
                throw new IllegalArgumentException("Source initialization binding lies outside its original authority");
            }
            ManagedOccurrenceBinding.verified(binding.occurrenceIdentity(), binding.bindingIdentity(),
                    binding.bindingPolicyIdentity(), binding.sourceDocumentId(), binding.sourceAddress(),
                    binding.targetDocumentId(), binding.expectedTargetBlueId(), binding.active(), binding.pendingHistoricalEpoch());
            if (binding.active() || binding.pendingHistoricalEpoch() != null) {
                blue.language.snapshot.FrozenNode reference = states.get(binding.sourceDocumentId()).frozenDocument().at(binding.sourcePath());
                if (reference == null || !reference.isReferenceOnly() || !binding.expectedTargetBlueId().equals(reference.getReferenceBlueId())
                        || binding.active() && owned.contains(binding.targetDocumentId())
                        && !states.get(binding.targetDocumentId()).blueId().equals(binding.expectedTargetBlueId())) {
                    throw new IllegalArgumentException("Source initialization binding differs from its original selected view");
                }
            }
            if (owned.contains(binding.targetDocumentId())) internal.add(binding);
        }
        ManagedDocumentGraph originalGraph = ManagedDocumentGraph.fromBindings(owned, internal);
        Set<Set<DocumentId>> actualPartition = new HashSet<Set<DocumentId>>();
        for (List<DocumentId> component : new SccPartitioner().partition(originalGraph)) actualPartition.add(new HashSet<DocumentId>(component));
        if (!declaredPartition.equals(actualPartition)) {
            throw new IllegalArgumentException("Source initialization components do not match the original binding graph");
        }
        for (ComponentSnapshot component : components) {
            boolean cyclic = component.orderedMemberDocumentIds().size() > 1
                    || originalGraph.hasSelfEdge(component.orderedMemberDocumentIds().get(0));
            if (cyclic != (component.kind() == ComponentKind.CYCLIC)) {
                throw new IllegalArgumentException("Source initialization component kind differs from its original binding graph");
            }
        }
    }

    private static Node marker(Node document, String name) {
        return NodePathEditor.getOrNull(document, "/contracts/" + name);
    }
}
