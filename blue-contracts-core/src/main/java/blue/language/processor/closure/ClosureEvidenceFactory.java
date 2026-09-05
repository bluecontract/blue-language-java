package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.CyclicSetProof;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contracts-owned constructors for live closure evidence.
 *
 * <p>Hosts provide semantic values and exact Language BlueIds; they never
 * reimplement the domain-separated RFC 8785/SHA-256 identity registry.  Every
 * method derives the asserted identities consumed by the ordinary public
 * closure value types.  The processor still independently verifies the
 * resulting invocation at admission.</p>
 */
public final class ClosureEvidenceFactory {

    private static final String UNBOUND_IDENTITY =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    /**
     * Prepares a closed-evidence replay for freshly reserved document births.
     *
     * <p>Existing heads, epochs, direct deliveries, logical cause, environment
     * and gas policy are preserved. Added targets are inactive prospective
     * members; the ordinary engine verifies their activation and initializes
     * them. No retained source epoch is executed merely by this preparation.
     * The complete semantic prefix is replayed and charged under the same cap;
     * this factory never resumes from a partially executed, gas-free suffix.</p>
     *
     * @param input exact input of the noncommitting resource attempt
     * @param births exact demands paired with fresh durable lineage reservations
     * @return expanded independently verifiable invocation; publishes nothing
     * @throws IllegalArgumentException for conflicting or unrelated evidence
     */
    public static ClosureInvocationInput withProspectiveBirths(
            ClosureInvocationInput input, List<ManagedDocumentBirth> births) {
        return ProspectiveBirthRetry.prepare(input, births);
    }

    private ClosureEvidenceFactory() {
    }

    /**
     * Captures one exact production environment from a configured processor.
     *
     * @param processor configured ordinary processor used by the closure
     * @param blueLanguageSpecificationIdentity selected Language specification
     * @param contractsSpecificationIdentity selected Contracts specification
     * @param managedDocumentPolicyLabel managed-document lineage policy label
     * @param managedBindingPolicyLabel managed occurrence policy label
     * @param exactNodeProviderDomainLabel exact-node provider domain label
     * @param externalOrderPolicyLabel external total-order policy label
     * @param portableLimitPolicyLabel portable-limit policy label
     * @param portableLimits complete named portable-limit map
     * @return complete identity-derived environment evidence
     */
    public static ClosureEnvironment environment(
            DocumentProcessor processor,
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            String managedDocumentPolicyLabel,
            String managedBindingPolicyLabel,
            String exactNodeProviderDomainLabel,
            String externalOrderPolicyLabel,
            String portableLimitPolicyLabel,
            Map<String, Long> portableLimits) {
        ClosureRuntimeDescriptor runtime = ClosureRuntimeDescriptor.capture(
                Objects.requireNonNull(processor, "processor"));
        ClosureEnvironment.LabeledIdentityEvidence documentPolicy = labeled(
                ClosureIdentityService.Constructor
                        .MANAGED_DOCUMENT_IDENTITY_POLICY,
                managedDocumentPolicyLabel);
        ClosureEnvironment.LabeledIdentityEvidence bindingPolicy = labeled(
                ClosureIdentityService.Constructor.MANAGED_BINDING_POLICY,
                managedBindingPolicyLabel);
        ClosureEnvironment.LabeledIdentityEvidence providerDomain = labeled(
                ClosureIdentityService.Constructor.EXACT_NODE_PROVIDER_DOMAIN,
                exactNodeProviderDomainLabel);
        ClosureEnvironment.LabeledIdentityEvidence orderPolicy = labeled(
                ClosureIdentityService.Constructor.EXTERNAL_ORDER_POLICY,
                externalOrderPolicyLabel);
        ClosureEnvironment.PortableLimitPolicyEvidence provisionalLimits =
                new ClosureEnvironment.PortableLimitPolicyEvidence(
                        UNBOUND_IDENTITY,
                        portableLimitPolicyLabel,
                        portableLimits);
        ClosureEnvironment.PortableLimitPolicyEvidence limits =
                new ClosureEnvironment.PortableLimitPolicyEvidence(
                        IDENTITIES.portableLimitPolicyIdentity(
                                provisionalLimits),
                        provisionalLimits.label(),
                        provisionalLimits.limits());
        return new ClosureEnvironment(
                blueLanguageSpecificationIdentity,
                contractsSpecificationIdentity,
                runtime.runtimeRegistryIdentity(),
                runtime.gasManifestIdentity(),
                documentPolicy,
                bindingPolicy,
                providerDomain,
                orderPolicy,
                limits,
                ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY,
                ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY);
    }

    /**
     * Derives one exact execution policy.
     *
     * @param sharedLimit closure-wide gas limit
     * @param localLimits optional per-document gas limits
     * @param label stable policy label
     * @return identity-derived policy evidence
     */
    public static ExecutionPolicy executionPolicy(
            long sharedLimit,
            Map<DocumentId, Long> localLimits,
            String label) {
        ExecutionPolicy provisional = new ExecutionPolicy(
                UNBOUND_IDENTITY, sharedLimit, localLimits, label);
        return new ExecutionPolicy(
                IDENTITIES.executionPolicyIdentity(provisional),
                provisional.sharedLimit(),
                provisional.localLimits(),
                provisional.label());
    }

    /**
     * Derives one exact external-entry cause.
     *
     * @param event exact event
     * @param eventBlueId exact event BlueId
     * @param sourceOrder verified external total-order key
     * @param externalOrderPolicyIdentity selected order-policy identity
     * @return identity-derived external cause
     */
    public static ExternalEventCause externalCause(
            Node event,
            String eventBlueId,
            ExternalOrderKey sourceOrder,
            String externalOrderPolicyIdentity) {
        String identity = IDENTITIES.externalCauseIdentity(
                eventBlueId, sourceOrder, externalOrderPolicyIdentity);
        return new ExternalEventCause(
                identity, event, eventBlueId, sourceOrder,
                externalOrderPolicyIdentity);
    }

    /**
     * Derives one exact admission cause and its selected policy identity.
     *
     * @param kind closed admission kind
     * @param label stable admission label
     * @param triggeringEventBlueId nullable triggering event BlueId
     * @param parentTransitionIdentity nullable parent transition identity
     * @param policyLabel selected admission-policy label
     * @return identity-derived admission cause
     */
    public static AdmissionCause admissionCause(
            AdmissionKind kind,
            String label,
            String triggeringEventBlueId,
            String parentTransitionIdentity,
            String policyLabel) {
        String policyIdentity = IDENTITIES.labeledIdentity(
                ClosureIdentityService.Constructor.ADMISSION_POLICY,
                policyLabel);
        String causeIdentity = IDENTITIES.admissionCauseIdentity(
                kind, label, triggeringEventBlueId,
                parentTransitionIdentity, policyIdentity);
        return new AdmissionCause(
                causeIdentity, kind, label, triggeringEventBlueId,
                parentTransitionIdentity, policyIdentity);
    }

    /**
     * Derives one authenticated contiguous child-revision cause.
     *
     * @param targetOccurrenceIdentity stable containing occurrence
     * @param childDocumentId revised child lineage
     * @param fromEpoch predecessor child epoch
     * @param toEpoch successor child epoch
     * @param beforeBlueId predecessor child BlueId
     * @param afterBlueId successor child BlueId
     * @param afterDocument exact successor child document
     * @param originalSourceCauseIdentity original external/admission cause
     * @return identity-derived source receipt and managed-revision cause
     */
    public static ManagedRevisionCause managedRevisionCause(
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Node afterDocument,
            String originalSourceCauseIdentity) {
        return managedRevisionCause(
                targetOccurrenceIdentity,
                childDocumentId,
                fromEpoch,
                toEpoch,
                beforeBlueId,
                afterBlueId,
                afterDocument,
                originalSourceCauseIdentity,
                null);
    }

    /**
     * Derives one authenticated child-revision cause with complete cyclic
     * successor evidence when required.
     *
     * @param targetOccurrenceIdentity stable containing occurrence
     * @param childDocumentId revised child lineage
     * @param fromEpoch predecessor child epoch
     * @param toEpoch successor child epoch
     * @param beforeBlueId predecessor child BlueId
     * @param afterBlueId successor child BlueId
     * @param afterDocument exact successor child document
     * @param originalSourceCauseIdentity original external/admission cause
     * @param afterCyclicProof complete successor cyclic-set proof, or
     *     {@code null} exactly for an ordinary successor
     * @return identity-derived source receipt and managed-revision cause
     */
    public static ManagedRevisionCause managedRevisionCause(
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Node afterDocument,
            String originalSourceCauseIdentity,
            CyclicSetProof afterCyclicProof) {
        String receiptIdentity = IDENTITIES.sourceRevisionReceiptIdentity(
                childDocumentId, fromEpoch, toEpoch,
                beforeBlueId, afterBlueId, originalSourceCauseIdentity);
        String causeIdentity = IDENTITIES.managedRevisionCauseIdentity(
                targetOccurrenceIdentity,
                childDocumentId,
                fromEpoch,
                toEpoch,
                beforeBlueId,
                afterBlueId,
                originalSourceCauseIdentity,
                receiptIdentity);
        return new ManagedRevisionCause(
                causeIdentity,
                targetOccurrenceIdentity,
                childDocumentId,
                fromEpoch,
                toEpoch,
                beforeBlueId,
                afterBlueId,
                afterDocument,
                originalSourceCauseIdentity,
                receiptIdentity,
                afterCyclicProof);
    }

    /**
     * Derives one managed-revision cause from a complete authenticated source
     * transition receipt.  The source receipt identity transitively binds its
     * ordered Root events into the cause and invocation identities.
     *
     * @param targetOccurrenceIdentity stable containing occurrence
     * @param fromEpoch predecessor source revision cursor
     * @param toEpoch successor source revision cursor
     * @param afterDocument exact successor source document
     * @param sourceTransitionReceipt complete Contracts source transition
     * @return identity-derived complete managed-revision cause
     */
    public static ManagedRevisionCause managedRevisionCause(
            String targetOccurrenceIdentity,
            long fromEpoch,
            long toEpoch,
            Node afterDocument,
            ManagedDocumentTransitionReceipt sourceTransitionReceipt) {
        return managedRevisionCause(
                targetOccurrenceIdentity,
                fromEpoch,
                toEpoch,
                afterDocument,
                sourceTransitionReceipt,
                null);
    }

    /**
     * Derives one typed-receipt managed revision with complete cyclic
     * successor evidence when required.
     *
     * @param targetOccurrenceIdentity stable containing occurrence
     * @param fromEpoch predecessor source revision cursor
     * @param toEpoch successor source revision cursor
     * @param afterDocument exact successor source document
     * @param sourceTransitionReceipt complete Contracts source transition
     * @param afterCyclicProof complete successor cyclic-set proof, or
     *     {@code null} exactly for an ordinary successor
     * @return identity-derived complete managed-revision cause
     */
    public static ManagedRevisionCause managedRevisionCause(
            String targetOccurrenceIdentity,
            long fromEpoch,
            long toEpoch,
            Node afterDocument,
            ManagedDocumentTransitionReceipt sourceTransitionReceipt,
            CyclicSetProof afterCyclicProof) {
        ManagedDocumentTransitionReceipt receipt = Objects.requireNonNull(
                sourceTransitionReceipt, "sourceTransitionReceipt");
        String causeIdentity = IDENTITIES.managedRevisionCauseIdentity(
                targetOccurrenceIdentity,
                receipt.documentId(),
                fromEpoch,
                toEpoch,
                receipt.beforeBlueId(),
                receipt.afterBlueId(),
                receipt.originalCauseIdentity(),
                receipt.transitionReceiptIdentity());
        return new ManagedRevisionCause(
                causeIdentity,
                targetOccurrenceIdentity,
                receipt.documentId(),
                fromEpoch,
                toEpoch,
                receipt.beforeBlueId(),
                receipt.afterBlueId(),
                afterDocument,
                receipt.originalCauseIdentity(),
                receipt,
                afterCyclicProof);
    }

    /**
     * Derives one acyclic singleton component state.
     *
     * @param document exact member record
     * @return identity-derived acyclic component
     */
    public static ComponentSnapshot acyclicComponent(
            ManagedDocumentSnapshot document) {
        ManagedDocumentSnapshot member = Objects.requireNonNull(
                document, "document");
        List<DocumentId> memberIds = Collections.singletonList(
                member.documentId());
        List<String> memberBlueIds = Collections.singletonList(
                member.blueId());
        String componentIdentity = IDENTITIES.componentIdentity(
                ComponentKind.ACYCLIC,
                member.componentGeneration(),
                memberIds);
        ComponentSnapshot provisional = new ComponentSnapshot(
                componentIdentity,
                UNBOUND_IDENTITY,
                member.componentGeneration(),
                ComponentKind.ACYCLIC,
                memberIds,
                memberBlueIds,
                null,
                null,
                null);
        return new ComponentSnapshot(
                componentIdentity,
                IDENTITIES.componentStateIdentity(provisional),
                member.componentGeneration(),
                ComponentKind.ACYCLIC,
                memberIds,
                memberBlueIds,
                null,
                null,
                null);
    }

    /**
     * Derives identities for one complete cyclic component record.
     *
     * @param componentGeneration component lineage generation
     * @param orderedMemberDocumentIds canonical member lineages
     * @param orderedMemberBlueIds parallel final MASTER suffix identities
     * @param masterBlueId final cyclic MASTER
     * @param completeCyclicProof complete representation-neutral proof
     * @return identity-derived cyclic component
     */
    public static ComponentSnapshot cyclicComponent(
            long componentGeneration,
            List<DocumentId> orderedMemberDocumentIds,
            List<String> orderedMemberBlueIds,
            String masterBlueId,
            CyclicSetProof completeCyclicProof) {
        String componentIdentity = IDENTITIES.componentIdentity(
                ComponentKind.CYCLIC,
                componentGeneration,
                orderedMemberDocumentIds);
        ComponentSnapshot proofDraft = new ComponentSnapshot(
                componentIdentity,
                UNBOUND_IDENTITY,
                componentGeneration,
                ComponentKind.CYCLIC,
                orderedMemberDocumentIds,
                orderedMemberBlueIds,
                masterBlueId,
                completeCyclicProof,
                UNBOUND_IDENTITY);
        String proofIdentity = IDENTITIES.cyclicProofIdentity(proofDraft);
        ComponentSnapshot stateDraft = new ComponentSnapshot(
                componentIdentity,
                UNBOUND_IDENTITY,
                componentGeneration,
                ComponentKind.CYCLIC,
                orderedMemberDocumentIds,
                orderedMemberBlueIds,
                masterBlueId,
                completeCyclicProof,
                proofIdentity);
        return new ComponentSnapshot(
                componentIdentity,
                IDENTITIES.componentStateIdentity(stateDraft),
                componentGeneration,
                ComponentKind.CYCLIC,
                orderedMemberDocumentIds,
                orderedMemberBlueIds,
                masterBlueId,
                completeCyclicProof,
                proofIdentity);
    }

    /**
     * Derives one complete state-only affected-closure snapshot.
     *
     * @param graphGeneration managed graph generation
     * @param managedDocuments exact document records
     * @param occurrences complete active and inactive occurrence inventory
     * @param components canonical target-before-source component partition
     * @param publicRootDocumentIds canonical public Root declarations
     * @return identity-derived authoritative snapshot
     */
    public static AffectedClosureSnapshot affectedClosure(
            long graphGeneration,
            List<ManagedDocumentSnapshot> managedDocuments,
            List<ManagedOccurrenceBinding> occurrences,
            List<ComponentSnapshot> components,
            List<DocumentId> publicRootDocumentIds) {
        List<ManagedDocumentSnapshot> documents = sortedDocuments(
                managedDocuments);
        List<ManagedOccurrenceBinding> rows = sortedOccurrences(occurrences);
        List<DocumentId> publicRoots = sortedDocumentIds(
                publicRootDocumentIds);
        String bindingSetIdentity = IDENTITIES.occurrenceBindingSetIdentity(
                rows);
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                UNBOUND_IDENTITY,
                graphGeneration,
                documents,
                rows,
                bindingSetIdentity,
                components,
                publicRoots);
        return new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisional),
                graphGeneration,
                documents,
                rows,
                bindingSetIdentity,
                components,
                publicRoots);
    }

    /**
     * Derives a complete processing invocation from semantic evidence.
     *
     * @param snapshot authoritative input closure
     * @param cause external or managed-revision cause
     * @param directDeliveries frozen direct-delivery snapshot
     * @param executionPolicy invocation gas policy
     * @param environment selected runtime environment
     * @return identity-derived PROCESS_CLOSURE input
     */
    public static ClosureInvocationInput processClosure(
            AffectedClosureSnapshot snapshot,
            ProcessingCause cause,
            List<DirectLogicalDelivery> directDeliveries,
            ExecutionPolicy executionPolicy,
            ClosureEnvironment environment) {
        List<DirectLogicalDelivery> deliveries = sortedDeliveries(
                directDeliveries);
        String deliveryIdentity = IDENTITIES.directDeliverySnapshotIdentity(
                deliveries);
        ClosureInvocationInput provisional =
                ClosureInvocationInput.processClosure(
                        UNBOUND_IDENTITY,
                        snapshot,
                        cause,
                        deliveries,
                        deliveryIdentity,
                        executionPolicy,
                        environment);
        return ClosureInvocationInput.processClosure(
                IDENTITIES.invocationIdentity(provisional),
                snapshot,
                cause,
                deliveries,
                deliveryIdentity,
                executionPolicy,
                environment);
    }

    /**
     * Derives complete admission input from semantic evidence.
     *
     * <p>This factory constructs immutable invocation evidence only; it does
     * not execute admission. Conforming execution uses
     * {@link BlueClosureContracts#admitClosureWithLifecycleQueue(
     * ClosureInvocationInput)}.</p>
     *
     * @param snapshot authoritative candidate closure
     * @param cause admission cause
     * @param candidate nullable invalid-evidence candidate
     * @param executionPolicy invocation gas policy
     * @param environment selected runtime environment
     * @return identity-derived ADMIT_CLOSURE input
     */
    public static ClosureInvocationInput admitClosure(
            AffectedClosureSnapshot snapshot,
            AdmissionCause cause,
            AdmissionCandidate candidate,
            ExecutionPolicy executionPolicy,
            ClosureEnvironment environment) {
        String candidateIdentity = candidate == null
                ? null
                : IDENTITIES.admissionCandidateIdentity(candidate);
        String emptyDeliveries = IDENTITIES.directDeliverySnapshotIdentity(
                Collections.<DirectLogicalDelivery>emptyList());
        ClosureInvocationInput provisional = ClosureInvocationInput.admitClosure(
                UNBOUND_IDENTITY,
                snapshot,
                cause,
                candidate,
                candidateIdentity,
                emptyDeliveries,
                executionPolicy,
                environment);
        return ClosureInvocationInput.admitClosure(
                IDENTITIES.invocationIdentity(provisional),
                snapshot,
                cause,
                candidate,
                candidateIdentity,
                emptyDeliveries,
                executionPolicy,
                environment);
    }

    private static ClosureEnvironment.LabeledIdentityEvidence labeled(
            ClosureIdentityService.Constructor constructor,
            String label) {
        return new ClosureEnvironment.LabeledIdentityEvidence(
                IDENTITIES.labeledIdentity(constructor, label),
                label);
    }

    private static List<ManagedDocumentSnapshot> sortedDocuments(
            List<ManagedDocumentSnapshot> values) {
        ArrayList<ManagedDocumentSnapshot> result =
                new ArrayList<ManagedDocumentSnapshot>(
                        Objects.requireNonNull(values, "managedDocuments"));
        Collections.sort(result);
        return result;
    }

    private static List<ManagedOccurrenceBinding> sortedOccurrences(
            List<ManagedOccurrenceBinding> values) {
        ArrayList<ManagedOccurrenceBinding> result =
                new ArrayList<ManagedOccurrenceBinding>(
                        Objects.requireNonNull(values, "occurrences"));
        Collections.sort(result);
        return result;
    }

    private static List<DocumentId> sortedDocumentIds(
            List<DocumentId> values) {
        ArrayList<DocumentId> result = new ArrayList<DocumentId>(
                Objects.requireNonNull(values, "publicRootDocumentIds"));
        Collections.sort(result);
        return result;
    }

    private static List<DirectLogicalDelivery> sortedDeliveries(
            List<DirectLogicalDelivery> values) {
        ArrayList<DirectLogicalDelivery> result =
                new ArrayList<DirectLogicalDelivery>(
                        Objects.requireNonNull(values, "directDeliveries"));
        Collections.sort(result);
        return result;
    }
}
