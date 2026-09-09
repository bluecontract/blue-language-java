package blue.language.processor.closure;

import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.ProcessorRuntimeAccess;
import blue.language.provider.CyclicSetProof;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Authoritative Phase-A identity and admission-evidence verifier. */
final class ClosureInvocationVerifier {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private ClosureInvocationVerifier() {
    }

    /**
     * Recomputes all closed invocation evidence before execution is allowed.
     *
     * <p>Malformed values and mismatched asserted identities fail with an
     * exception.  A structurally valid admission candidate instead returns a
     * semantic disposition, because a negative candidate is ordinary
     * invocation evidence and not malformed transport.</p>
     */
    static Verification verify(
            ClosureInvocationInput input,
            Supplier<ProcessorRuntimeAccess> runtimeAccess) {
        ClosureInvocationInput selected = Objects.requireNonNull(
                input, "input");

        ClosureEvidenceVerifier.verifySnapshot(selected.snapshot());
        verifyEnvironment(selected.environment());
        requireClaim(
                "inputClosureIdentity",
                selected.snapshot().closureIdentity(),
                IDENTITIES.affectedClosureIdentity(selected.snapshot()));
        verifyAuthoritativeBindings(selected);
        ExactEventIdentityEvidence externalEventIdentityEvidence =
                verifyCause(selected.cause(), runtimeAccess);

        requireClaim(
                "gasPolicyIdentity",
                selected.executionPolicy().identity(),
                IDENTITIES.executionPolicyIdentity(
                        selected.executionPolicy()));
        requireClaim(
                "directDeliverySnapshotIdentity",
                selected.directDeliverySnapshotIdentity(),
                IDENTITIES.directDeliverySnapshotIdentity(
                        selected.directDeliveries()));

        AdmissionCandidate candidate = selected.admissionCandidate();
        if (candidate != null) {
            requireClaim(
                    "admissionCandidateIdentity",
                    selected.admissionCandidateIdentity(),
                    IDENTITIES.admissionCandidateIdentity(candidate));
        }

        requireClaim(
                "invocationIdentity",
                selected.invocationIdentity(),
                IDENTITIES.invocationIdentity(selected));

        CandidateDisposition disposition = candidate == null
                ? CandidateDisposition.NOT_SUBMITTED
                : verifyCandidate(selected, candidate)
                ? CandidateDisposition.SEMANTICALLY_VALID
                : CandidateDisposition.SEMANTICALLY_INVALID;
        return new Verification(
                selected.invocationIdentity(), disposition,
                candidate == null ? null : candidate.kind(),
                externalEventIdentityEvidence);
    }

    /** Verifies one additive demand-bound processing retry. */
    static Verification verifyRetry(
            ClosureProcessRetryInput retry,
            Supplier<ProcessorRuntimeAccess> runtimeAccess) {
        ClosureProcessRetryInput selected = Objects.requireNonNull(
                retry, "retry");
        ClosureInvocationInput base = selected.baseInvocation();
        Verification verified = verify(base, runtimeAccess);
        for (ManagedOccurrenceEvidenceResolution resolution
                : selected.resolutions()) {
            ManagedOccurrenceEvidenceDemand demand = resolution.demand();
            if (!demand.logicalCauseIdentity().equals(
                            base.cause().causeIdentity())
                    || !demand.inputClosureIdentity().equals(
                            base.snapshot().closureIdentity())
                    || demand.inputGraphGeneration()
                            != base.snapshot().graphGeneration()) {
                throw new IllegalArgumentException(
                        "Managed-occurrence resolution belongs to another "
                                + "base invocation");
            }
            ManagedDocumentSnapshot source = base.snapshot()
                    .managedDocument(demand.sourceDocumentId());
            ManagedDocumentSnapshot target = base.snapshot()
                    .managedDocument(resolution.targetDocumentId());
            if (source == null || target == null) {
                throw new IllegalArgumentException(
                        "Managed-occurrence resolution endpoint is outside "
                                + "the base closure");
            }
            ManagedOccurrenceBinding active = null;
            for (ManagedOccurrenceBinding binding
                    : base.snapshot().occurrences()) {
                if (binding.sourceDocumentId().equals(
                                demand.sourceDocumentId())
                        && binding.sourcePath().equals(
                                demand.sourcePath())) {
                    active = binding;
                    break;
                }
            }
            if (active == null || !(active.active()
                    || resolution.selectsInactiveReservation(base.snapshot(), active)
                    || (isVerifiedManagedReceiptEventSource(base, active)
                            && !active.targetDocumentId().equals(
                                    resolution.targetDocumentId())))) {
                throw new IllegalArgumentException(
                        "A managed-occurrence process retry requires an "
                                + "active source row, initialized same-lineage reservation, or verified different-lineage receipt event");
            }
        }
        return new Verification(
                selected.retryInvocationIdentity(),
                verified.candidateDisposition(),
                verified.candidateKind(),
                verified.externalEventIdentityEvidence());
    }

    private static boolean isVerifiedManagedReceiptEventSource(
            ClosureInvocationInput base,
            ManagedOccurrenceBinding binding) {
        if (!(base.cause() instanceof ManagedRevisionCause)
                || binding.active()
                || binding.pendingHistoricalEpoch() == null) {
            return false;
        }
        ManagedRevisionCause revision = (ManagedRevisionCause) base.cause();
        // verify(base) has already authenticated the complete immutable
        // receipt and its ordered Root-event identities. The retry cannot
        // substitute another occurrence, generation, source, or before state.
        // Runtime consumption still requires this exact demand to arise from
        // the authenticated imported event before any resolution is applied.
        return revision.sourceTransitionReceipt().isPresent()
                && !revision.sourceTransitionReceipt().get().emittedRootEvents().isEmpty()
                && binding.occurrenceIdentity().equals(revision.targetOccurrenceIdentity())
                && binding.targetDocumentId().equals(revision.childDocumentId())
                && binding.pendingHistoricalEpoch().longValue() == revision.fromEpoch()
                && binding.expectedTargetBlueId().equals(revision.beforeBlueId());
    }

    private static void verifyEnvironment(ClosureEnvironment environment) {
        requireLabeledEnvironmentIdentity(
                "managedDocumentIdentityPolicyIdentity",
                environment.managedDocumentIdentityPolicy(),
                ClosureIdentityService.Constructor
                        .MANAGED_DOCUMENT_IDENTITY_POLICY);
        requireLabeledEnvironmentIdentity(
                "managedBindingPolicyIdentity",
                environment.managedBindingPolicy(),
                ClosureIdentityService.Constructor.MANAGED_BINDING_POLICY);
        requireLabeledEnvironmentIdentity(
                "exactNodeProviderDomainIdentity",
                environment.exactNodeProviderDomain(),
                ClosureIdentityService.Constructor.EXACT_NODE_PROVIDER_DOMAIN);
        requireLabeledEnvironmentIdentity(
                "externalOrderPolicyIdentity",
                environment.externalOrderPolicy(),
                ClosureIdentityService.Constructor.EXTERNAL_ORDER_POLICY);
        requireClaim(
                "portableLimitPolicyIdentity",
                environment.portableLimitPolicyIdentity(),
                IDENTITIES.portableLimitPolicyIdentity(
                        environment.portableLimitPolicy()));
    }

    private static void requireLabeledEnvironmentIdentity(
            String field,
            ClosureEnvironment.LabeledIdentityEvidence evidence,
            ClosureIdentityService.Constructor constructor) {
        requireClaim(
                field,
                evidence.identity(),
                IDENTITIES.labeledIdentity(constructor, evidence.label()));
    }

    private static void verifyAuthoritativeBindings(
            ClosureInvocationInput input) {
        String selectedPolicy = input.environment()
                .managedBindingPolicyIdentity();
        for (ManagedOccurrenceBinding binding
                : input.snapshot().occurrences()) {
            if (!selectedPolicy.equals(binding.bindingPolicyIdentity())) {
                throw new IllegalArgumentException(
                        "Authoritative occurrence uses a different binding policy");
            }
            String occurrenceIdentity = IDENTITIES.managedOccurrenceIdentity(
                    binding.sourceDocumentId(),
                    binding.sourceAddress(),
                    binding.targetDocumentId(),
                    binding.bindingPolicyIdentity());
            requireClaim("occurrenceIdentity",
                    binding.occurrenceIdentity(), occurrenceIdentity);
            String bindingIdentity =
                    IDENTITIES.managedOccurrenceBindingIdentity(
                            binding.sourceDocumentId(),
                            binding.sourceAddress(),
                            binding.targetDocumentId(),
                            binding.expectedTargetBlueId(),
                            binding.bindingPolicyIdentity());
            requireClaim("bindingIdentity",
                    binding.bindingIdentity(), bindingIdentity);
        }
    }

    private static ExactEventIdentityEvidence verifyCause(
            ProcessingCause cause,
            Supplier<ProcessorRuntimeAccess> runtimeAccess) {
        ProcessingCause selected = Objects.requireNonNull(cause, "cause");
        ExactEventIdentityEvidence externalEventIdentityEvidence = null;
        if (selected instanceof ExternalEventCause) {
            ExternalEventCause external = (ExternalEventCause) selected;
            if (BlueIds.hasCyclicMemberSeparator(
                    external.eventBlueId())) {
                throw new IllegalArgumentException(
                        "A cyclic-set member identity must not be used as a "
                                + "top-level external event");
            }
            externalEventIdentityEvidence = verifyExternalEvent(
                    external, runtimeAccess);
        } else if (selected instanceof ManagedHistoryStep) {
            ManagedHistoryStep revision = (ManagedHistoryStep) selected;
            Optional<CyclicSetProof> cyclicProof =
                    revision.afterCyclicProof();
            if (cyclicProof.isPresent()) {
                ManagedRevisionCyclicEvidenceVerifier.verify(
                        revision.afterBlueId(),
                        revision.afterDocument(),
                        cyclicProof.get());
            } else {
                requireClaim(
                        "afterBlueId",
                        revision.afterBlueId(),
                        DirectBlueIdCalculator.calculateBlueId(
                                revision.afterDocument()));
            }
            if (revision.sourceTransitionReceipt().isPresent()) {
                ManagedDocumentTransitionReceipt receipt = revision
                        .sourceTransitionReceipt().get();
                requireClaim(
                        "sourceRevisionReceiptIdentity",
                        revision.sourceRevisionReceiptIdentity(),
                        receipt.transitionReceiptIdentity());
                if (!revision.childDocumentId().equals(receipt.documentId())
                        || !revision.beforeBlueId().equals(
                            receipt.beforeBlueId())
                        || !revision.afterBlueId().equals(
                            receipt.afterBlueId())
                        || !revision.originalSourceCauseIdentity().equals(
                            receipt.originalCauseIdentity())) {
                    throw new IllegalArgumentException(
                            "Complete source transition receipt disagrees with managed revision");
                }
            } else {
                requireClaim(
                        "sourceRevisionReceiptIdentity",
                        revision.sourceRevisionReceiptIdentity(),
                        IDENTITIES.sourceRevisionReceiptIdentity(
                                revision.childDocumentId(),
                                revision.fromEpoch(),
                                revision.toEpoch(),
                                revision.beforeBlueId(),
                                revision.afterBlueId(),
                                revision.originalSourceCauseIdentity()));
            }
        }
        requireClaim(
                "causeIdentity",
                selected.causeIdentity(),
                IDENTITIES.causeIdentity(selected));
        return externalEventIdentityEvidence;
    }

    /** Acquires Language runtime state only when Source canonicalization needs it. */
    private static ExactEventIdentityEvidence verifyExternalEvent(
            ExternalEventCause external,
            Supplier<ProcessorRuntimeAccess> runtimeAccess) {
        try {
            return ExactEventIdentityEvidence.verify(
                    null,
                    external.event(),
                    external.eventBlueId(),
                    null);
        } catch (IllegalStateException runtimeRequired) {
            if (runtimeAccess == null) {
                throw runtimeRequired;
            }
            return ExactEventIdentityEvidence.verify(
                    Objects.requireNonNull(
                            runtimeAccess.get(),
                            "runtimeAccess for external event verification"),
                    external.event(),
                    external.eventBlueId(),
                    null);
        }
    }

    private static boolean verifyCandidate(
            ClosureInvocationInput input,
            AdmissionCandidate candidate) {
        switch (candidate.kind()) {
            case BAD_CYCLIC_PROOF:
                return verifyCandidateProof(
                        input.snapshot(),
                        ((AdmissionCandidate.BadCyclicProof) candidate)
                                .candidateCyclicProof());
            case AMBIGUOUS_PRELIMINARY_MEMBERS:
                return verifyPreliminaryMembers(
                        ((AdmissionCandidate.AmbiguousPreliminaryMembers)
                        candidate).candidateCyclicMembers());
            case INVALID_OCCURRENCE_BINDING:
                return verifyCandidateBindings(
                        input,
                        ((AdmissionCandidate.InvalidOccurrenceBinding)
                        candidate).candidateOccurrenceBindings());
            default:
                throw new AssertionError(
                        "Unhandled admission candidate " + candidate.kind());
        }
    }

    private static boolean verifyCandidateProof(
            AffectedClosureSnapshot snapshot,
            AdmissionCandidate.CandidateCyclicProof proof) {
        ComponentSnapshot component = null;
        for (ComponentSnapshot item : snapshot.components()) {
            if (item.componentIdentity().equals(proof.componentIdentity())) {
                component = item;
                break;
            }
        }
        if (component == null || component.kind() != ComponentKind.CYCLIC
                || !component.masterBlueId().equals(proof.masterBlueId())
                || component.orderedMemberDocumentIds().size()
                != proof.memberStates().size()) {
            return false;
        }
        for (int index = 0; index < proof.memberStates().size(); index++) {
            AdmissionCandidate.CandidateMemberState state =
                    proof.memberStates().get(index);
            if (!component.orderedMemberDocumentIds().get(index).equals(
                    state.documentId())
                    || !component.orderedMemberBlueIds().get(index).equals(
                    state.blueId())) {
                return false;
            }
        }
        List<Node> expected = component.completeCyclicProof()
                .declaredPlaceholderSet();
        List<Node> submitted = proof.declaredPlaceholderSet();
        if (expected.size() != submitted.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!NodeWireForm.get(
                    expected.get(index), NodeWireForm.Strategy.SIMPLE)
                    .equals(NodeWireForm.get(
                            submitted.get(index),
                            NodeWireForm.Strategy.SIMPLE))) {
                return false;
            }
        }
        return true;
    }

    private static boolean verifyPreliminaryMembers(
            List<AdmissionCandidate.CandidateCyclicMember> members) {
        java.util.ArrayList<Node> documents =
                new java.util.ArrayList<Node>(members.size());
        for (AdmissionCandidate.CandidateCyclicMember member : members) {
            documents.add(member.document());
        }
        try {
            CircularSetIdentityCalculator
                    .calculateCircularSetFinalization(documents);
            return true;
        } catch (IllegalArgumentException invalidCandidate) {
            return false;
        }
    }

    private static boolean verifyCandidateBindings(
            ClosureInvocationInput input,
            List<AdmissionCandidate.CandidateOccurrenceBinding> bindings) {
        Set<String> occurrenceIdentities = new HashSet<String>();
        Set<String> bindingIdentities = new HashSet<String>();
        Set<String> sourceRoutes = new HashSet<String>();
        for (AdmissionCandidate.CandidateOccurrenceBinding binding
                : bindings) {
            if (!occurrenceIdentities.add(binding.occurrenceIdentity())
                    || !bindingIdentities.add(binding.bindingIdentity())
                    || !sourceRoutes.add(binding.sourceDocumentId().value()
                    + "\u0000" + binding.sourcePath())
                    || !binding.bindingPolicyIdentity().equals(
                    input.environment().managedBindingPolicyIdentity())) {
                return false;
            }
            ScopeAddress address = ScopeAddress.embedded(
                    binding.sourcePath(), binding.activationGeneration());
            if (!binding.occurrenceIdentity().equals(
                    IDENTITIES.managedOccurrenceIdentity(
                            binding.sourceDocumentId(),
                            address,
                            binding.targetDocumentId(),
                            binding.bindingPolicyIdentity()))
                    || !binding.bindingIdentity().equals(
                    IDENTITIES.managedOccurrenceBindingIdentity(
                            binding.sourceDocumentId(),
                            address,
                            binding.targetDocumentId(),
                            binding.expectedTargetBlueId(),
                            binding.bindingPolicyIdentity()))) {
                return false;
            }
            ManagedDocumentSnapshot source = input.snapshot().managedDocument(
                    binding.sourceDocumentId());
            ManagedDocumentSnapshot target = input.snapshot().managedDocument(
                    binding.targetDocumentId());
            if (source == null || target == null) {
                return false;
            }
            if (!binding.active()) {
                continue;
            }
            if (!target.blueId().equals(binding.expectedTargetBlueId())) {
                return false;
            }
            Node value = NodePathEditor.getOrNull(
                    source.document(), binding.sourcePath());
            if (!ManagedOccurrenceTargetVerifier.establishesExactTarget(
                    value, target)) {
                return false;
            }
        }
        return true;
    }

    private static void requireClaim(
            String field, String claimed, String calculated) {
        if (!Objects.equals(claimed, calculated)) {
            throw new IllegalArgumentException(field
                    + " mismatch: claimed=" + claimed
                    + ", calculated=" + calculated);
        }
    }

    /** Semantic disposition of an optional, structurally valid candidate. */
    enum CandidateDisposition {
        /** Invocation submitted no candidate. */
        NOT_SUBMITTED,
        /** The selected semantic verifier accepted the candidate. */
        SEMANTICALLY_VALID,
        /** The selected semantic verifier rejected the candidate. */
        SEMANTICALLY_INVALID
    }

    /** Immutable result of complete Phase-A evidence verification. */
    static final class Verification {
        private final String invocationIdentity;
        private final CandidateDisposition candidateDisposition;
        private final AdmissionCandidate.Kind candidateKind;
        private final ExactEventIdentityEvidence
                externalEventIdentityEvidence;

        private Verification(
                String invocationIdentity,
                CandidateDisposition candidateDisposition,
                AdmissionCandidate.Kind candidateKind,
                ExactEventIdentityEvidence externalEventIdentityEvidence) {
            this.invocationIdentity = invocationIdentity;
            this.candidateDisposition = candidateDisposition;
            this.candidateKind = candidateKind;
            this.externalEventIdentityEvidence =
                    externalEventIdentityEvidence;
        }

        String invocationIdentity() {
            return invocationIdentity;
        }

        CandidateDisposition candidateDisposition() {
            return candidateDisposition;
        }

        AdmissionCandidate.Kind candidateKind() {
            return candidateKind;
        }

        /**
         * Returns the already verified external-event capability, if any.
         *
         * <p>Consumers must carry this evidence across the Phase-A/Phase-B
         * boundary instead of independently re-admitting the detached event
         * and identity pair.</p>
         */
        ExactEventIdentityEvidence externalEventIdentityEvidence() {
            return externalEventIdentityEvidence;
        }
    }
}
