package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE;
import static blue.language.processor.util.ProcessorContractConstants.KEY_INITIALIZED;
import static blue.language.processor.util.ProcessorContractConstants.KEY_TERMINATED;

/**
 * Authoritative constructor for Contracts 1.0 platform-evidence identities.
 *
 * <p>Every ordinary constructor hashes the RFC 8785 UTF-8 encoding of the
 * exact envelope {@code {"domain": ..., "value": ...}} with SHA-256.  This
 * class deliberately uses the same JCS implementation as the Language
 * identity path.  It retains explicit {@code null} fields, rejects non-NFC
 * text and non-portable integers, and validates each constructor's closed
 * top-level shape before hashing.</p>
 *
 * <p>The checkpoint-domain recipe is the one registry exception: it delegates
 * to the unchanged Blue Language direct-BlueId calculator and does not use an
 * envelope.</p>
 */
final class ClosureIdentityService {

    /** Shared stateless service. */
    static final ClosureIdentityService INSTANCE =
            new ClosureIdentityService();

    private static final ObjectMapper IDENTITY_MAPPER = identityMapper();

    /** Closed Contracts 1.0 SHA-256-envelope constructor registry. */
    enum Constructor {
        MANAGED_DOCUMENT_IDENTITY_POLICY(
                "blue-contracts-managed-document-identity-policy/1.0",
                "label"),
        MANAGED_BINDING_POLICY(
                "blue-contracts-managed-binding-policy/1.0", "label"),
        MANAGED_OCCURRENCE(
                "blue-contracts-managed-occurrence-lineage/1.0",
                "sourceDocumentId", "sourcePath", "activationGeneration",
                "targetDocumentId", "bindingPolicyIdentity"),
        MANAGED_OCCURRENCE_BINDING(
                "blue-contracts-managed-occurrence/1.0",
                "sourceDocumentId", "sourcePath", "activationGeneration",
                "targetDocumentId", "expectedTargetBlueId",
                "bindingPolicyIdentity"),
        MANAGED_SCOPE_KEY(
                "blue-contracts-managed-scope-key/1.0",
                "documentId", "scopePath", "activationGeneration"),
        COMPONENT(
                "blue-contracts-component/1.0",
                "kind", "generation", "members"),
        CYCLIC_PROOF(
                "blue-contracts-cyclic-proof-evidence/1.0",
                "componentIdentity", "masterBlueId", "memberStates",
                "declaredPlaceholderSet"),
        COMPONENT_STATE(
                "blue-contracts-component-state/1.0",
                "componentIdentity", "memberStates", "masterBlueId",
                "cyclicProofIdentity"),
        ADMISSION_POLICY(
                "blue-contracts-admission-policy/1.0", "label"),
        EXACT_NODE_PROVIDER_DOMAIN(
                "blue-contracts-exact-node-provider-domain/1.0", "label"),
        EXTERNAL_ORDER_POLICY(
                "blue-contracts-external-order-policy/1.0", "label"),
        PORTABLE_LIMIT_POLICY(
                "blue-contracts-portable-limit-policy/1.0",
                "label", "limits"),
        ADMISSION_CAUSE(
                "blue-contracts-admission-cause/1.0",
                "admissionKind", "label", "triggeringEventBlueId",
                "parentTransitionIdentity", "policyIdentity"),
        SOURCE_REVISION_RECEIPT(
                "blue-contracts-source-revision-receipt/1.0",
                "childDocumentId", "fromEpoch", "toEpoch", "beforeBlueId",
                "afterBlueId", "originalSourceCauseIdentity"),
        MANAGED_REVISION_CAUSE(
                "blue-contracts-managed-revision-cause/1.0",
                "targetOccurrenceIdentity", "childDocumentId", "fromEpoch",
                "toEpoch", "beforeBlueId", "afterBlueId",
                "originalSourceCauseIdentity",
                "sourceRevisionReceiptIdentity"),
        ADMISSION_CANDIDATE(
                "blue-contracts-admission-candidate/1.0",
                "kind", "evidence"),
        EXTERNAL_CAUSE(
                "blue-contracts-external-cause/1.0",
                "eventBlueId", "sourceOrder", "externalOrderPolicyIdentity"),
        EXECUTION_POLICY(
                "blue-contracts-execution-policy/1.0",
                "sharedLimit", "localLimits", "label"),
        DIRECT_DELIVERY(
                "blue-contracts-direct-delivery/1.0",
                "targetDocumentId", "scopePath", "activationGeneration",
                "channelKey", "logicalDeliveryKey", "rawOccurrenceOrder"),
        DIRECT_DELIVERY_SNAPSHOT(
                "blue-contracts-direct-delivery-snapshot/1.0"),
        OCCURRENCE_BINDING_SET(
                "blue-contracts-occurrence-binding-set/1.0"),
        AFFECTED_CLOSURE(
                "blue-contracts-affected-closure/1.0",
                "graphGeneration", "documents",
                "occurrenceBindingSetIdentity", "components",
                "publicRootDocumentIds"),
        INVOCATION(
                "blue-contracts-invocation/1.0",
                "operation", "causeIdentity", "admissionCandidateIdentity",
                "inputGraphGeneration", "inputClosureIdentity", "documents",
                "directDeliverySnapshotIdentity",
                "occurrenceBindingSetIdentity", "runtimeRegistryIdentity",
                "gasPolicyIdentity", "cyclicFinalizerIdentity",
                "cyclicProofVerifierIdentity",
                "blueLanguageSpecificationIdentity",
                "contractsSpecificationIdentity",
                "managedDocumentIdentityPolicyIdentity",
                "managedBindingPolicyIdentity",
                "exactNodeProviderDomainIdentity",
                "externalOrderPolicyIdentity", "gasManifestIdentity",
                "portableLimitPolicyIdentity"),
        TRANSITION_OCCURRENCE(
                "blue-contracts-transition-occurrence/1.0",
                "invocationIdentity", "transitionOrdinal", "targetDocumentId",
                "beforeBlueId", "causingWorkOccurrenceIdentity"),
        EVENT_OCCURRENCE(
                "blue-contracts-event-occurrence/1.0",
                "invocationIdentity", "eventOccurrenceOrdinal", "eventBlueId"),
        WORK_OCCURRENCE(
                "blue-contracts-work-occurrence/1.0",
                "invocationIdentity", "workOrdinal", "workKind",
                "targetManagedScopeIdentity", "sourceOccurrenceIdentity"),
        CHANNEL_OCCURRENCE(
                "blue-contracts-channel-occurrence/1.0",
                "managedDocumentId", "scopePath",
                "scopeActivationGeneration", "rawChannelKey",
                "effectiveRuntimeContributionBlueId",
                "subscriptionHeaderBlueId"),
        SUBSCRIPTION(
                "blue-contracts-subscription/1.0",
                "channelOccurrenceIdentity", "documentBlueId",
                "graphGeneration", "componentGeneration"),
        GRAPH_CHANGES("blue-contracts-graph-changes/1.0"),
        CHECKPOINT_WRITES("blue-contracts-checkpoint-writes/1.0"),
        SUBSCRIPTION_DELTAS("blue-contracts-subscription-deltas/1.0"),
        PUBLIC_EVENTS("blue-contracts-public-events/1.0"),
        REJECTED_CHARGE(
                "blue-contracts-rejected-charge/1.0",
                "namespace", "counter", "quantity", "weight", "subtotal",
                "applicableCap", "remainingBeforeCharge", "owner"),
        GAS_TRACE("blue-contracts-gas-trace/1.0"),
        PLATFORM_COMMIT_COMPANION(
                "blue-contracts-platform-commit-companion/1.0",
                "invocationIdentity", "inputClosureIdentity",
                "outputClosureIdentity", "expectedInputGraphGeneration",
                "expectedInputDocuments", "expectedInputComponents",
                "inputOccurrenceBindingSetIdentity", "outputGraphGeneration",
                "resultingDocuments", "resultingComponents",
                "occurrenceBindingSetIdentity", "graphChangesIdentity",
                "checkpointWritesIdentity", "subscriptionDeltasIdentity",
                "publicEventsIdentity", "gasTraceIdentity",
                "blueLanguageSpecificationIdentity",
                "contractsSpecificationIdentity",
                "managedDocumentIdentityPolicyIdentity",
                "managedBindingPolicyIdentity",
                "exactNodeProviderDomainIdentity",
                "externalOrderPolicyIdentity", "runtimeRegistryIdentity",
                "gasManifestIdentity", "portableLimitPolicyIdentity",
                "cyclicFinalizerIdentity", "cyclicProofVerifierIdentity");

        private final String domain;
        private final List<String> fields;

        Constructor(String domain, String... fields) {
            this.domain = domain;
            this.fields = Collections.unmodifiableList(
                    Arrays.asList(fields.clone()));
        }

        /** @return exact versioned domain text */
        String domain() {
            return domain;
        }
    }

    /** Creates a stateless identity service. */
    ClosureIdentityService() {
    }

    /**
     * Constructs any registered SHA-256 envelope identity.
     *
     * @param constructor exact registry constructor
     * @param value exact JSON-compatible constructor value
     * @return lowercase {@code sha256:} identity
     */
    String identity(Constructor constructor, Object value) {
        Constructor selected = Objects.requireNonNull(
                constructor, "constructor");
        Object admitted = copyPortableValue(value, OBJECT_VALUE);
        validateConstructor(selected, admitted);
        LinkedHashMap<String, Object> envelope = objectValue();
        envelope.put("domain", selected.domain);
        envelope.put(OBJECT_VALUE, admitted);
        return "sha256:" + hex(sha256(canonicalBytes(envelope)));
    }

    /** Constructs a one-label policy or environment-domain identity. */
    String labeledIdentity(Constructor constructor, String label) {
        switch (Objects.requireNonNull(constructor, "constructor")) {
            case MANAGED_DOCUMENT_IDENTITY_POLICY:
            case MANAGED_BINDING_POLICY:
            case ADMISSION_POLICY:
            case EXACT_NODE_PROVIDER_DOMAIN:
            case EXTERNAL_ORDER_POLICY:
                break;
            default:
                throw new IllegalArgumentException(
                        "Constructor is not a one-label identity");
        }
        LinkedHashMap<String, Object> value = objectValue();
        value.put("label", label);
        return identity(constructor, value);
    }

    /** Constructs the complete named portable-limit-policy identity. */
    String portableLimitPolicyIdentity(
            ClosureEnvironment.PortableLimitPolicyEvidence policy) {
        ClosureEnvironment.PortableLimitPolicyEvidence selected =
                Objects.requireNonNull(policy, "policy");
        ArrayList<Object> limits = new ArrayList<Object>();
        for (Map.Entry<String, Long> entry : selected.limits().entrySet()) {
            LinkedHashMap<String, Object> limit = objectValue();
            limit.put("name", entry.getKey());
            limit.put(OBJECT_VALUE, entry.getValue());
            limits.add(limit);
        }
        LinkedHashMap<String, Object> value = objectValue();
        value.put("label", selected.label());
        value.put("limits", limits);
        return identity(Constructor.PORTABLE_LIMIT_POLICY, value);
    }

    /** Constructs stable occurrence-lineage identity from typed evidence. */
    String managedOccurrenceIdentity(
            DocumentId sourceDocumentId,
            ScopeAddress sourceAddress,
            DocumentId targetDocumentId,
            String bindingPolicyIdentity) {
        requireEmbedded(sourceAddress);
        LinkedHashMap<String, Object> value = objectValue();
        value.put("sourceDocumentId", sourceDocumentId.value());
        value.put("sourcePath", sourceAddress.path());
        value.put("activationGeneration",
                Long.valueOf(sourceAddress.activationGeneration()));
        value.put("targetDocumentId", targetDocumentId.value());
        value.put("bindingPolicyIdentity", bindingPolicyIdentity);
        return identity(Constructor.MANAGED_OCCURRENCE, value);
    }

    /** Constructs exact state-specific occurrence-binding identity. */
    String managedOccurrenceBindingIdentity(
            DocumentId sourceDocumentId,
            ScopeAddress sourceAddress,
            DocumentId targetDocumentId,
            String expectedTargetBlueId,
            String bindingPolicyIdentity) {
        requireEmbedded(sourceAddress);
        LinkedHashMap<String, Object> value = objectValue();
        value.put("sourceDocumentId", sourceDocumentId.value());
        value.put("sourcePath", sourceAddress.path());
        value.put("activationGeneration",
                Long.valueOf(sourceAddress.activationGeneration()));
        value.put("targetDocumentId", targetDocumentId.value());
        value.put("expectedTargetBlueId", expectedTargetBlueId);
        value.put("bindingPolicyIdentity", bindingPolicyIdentity);
        return identity(Constructor.MANAGED_OCCURRENCE_BINDING, value);
    }

    /** Constructs complete managed-scope key identity. */
    String managedScopeKeyIdentity(ManagedScopeKey key) {
        ManagedScopeKey selected = Objects.requireNonNull(key, "key");
        LinkedHashMap<String, Object> value = objectValue();
        value.put("documentId", selected.documentId().value());
        value.put("scopePath", selected.address().path());
        value.put("activationGeneration",
                Long.valueOf(selected.address().activationGeneration()));
        return identity(Constructor.MANAGED_SCOPE_KEY, value);
    }

    /** Constructs stable component lineage identity. */
    String componentIdentity(
            ComponentKind kind,
            long generation,
            List<DocumentId> members) {
        ArrayList<Object> memberValues = new ArrayList<Object>();
        for (DocumentId member : Objects.requireNonNull(members, "members")) {
            memberValues.add(Objects.requireNonNull(member, "member").value());
        }
        LinkedHashMap<String, Object> value = objectValue();
        value.put("kind", Objects.requireNonNull(kind, "kind").name());
        value.put("generation", Long.valueOf(generation));
        value.put("members", memberValues);
        return identity(Constructor.COMPONENT, value);
    }

    /** Reconstructs and verifies the stable identity asserted by a component. */
    String componentIdentity(ComponentSnapshot component) {
        ComponentSnapshot selected = Objects.requireNonNull(
                component, "component");
        return componentIdentity(selected.kind(),
                selected.componentGeneration(),
                selected.orderedMemberDocumentIds());
    }

    /** Constructs the complete cyclic proof-evidence identity. */
    String cyclicProofIdentity(ComponentSnapshot component) {
        ComponentSnapshot selected = Objects.requireNonNull(
                component, "component");
        if (selected.kind() != ComponentKind.CYCLIC) {
            throw new IllegalArgumentException(
                    "Cyclic proof identity requires a cyclic component");
        }
        LinkedHashMap<String, Object> value = objectValue();
        value.put("componentIdentity", selected.componentIdentity());
        value.put("masterBlueId", selected.masterBlueId());
        value.put("memberStates", memberStates(selected));
        value.put("declaredPlaceholderSet",
                proofWireValue(selected.completeCyclicProof()));
        return identity(Constructor.CYCLIC_PROOF, value);
    }

    /** Constructs exact component-state identity. */
    String componentStateIdentity(ComponentSnapshot component) {
        ComponentSnapshot selected = Objects.requireNonNull(
                component, "component");
        LinkedHashMap<String, Object> value = objectValue();
        value.put("componentIdentity", selected.componentIdentity());
        value.put("memberStates", memberStates(selected));
        value.put("masterBlueId", selected.masterBlueId());
        value.put("cyclicProofIdentity", selected.cyclicProofIdentity());
        return identity(Constructor.COMPONENT_STATE, value);
    }

    /** Constructs an exact external-cause identity. */
    String externalCauseIdentity(
            String eventBlueId,
            ExternalOrderKey sourceOrder,
            String externalOrderPolicyIdentity) {
        LinkedHashMap<String, Object> value = objectValue();
        value.put("eventBlueId", eventBlueId);
        value.put("sourceOrder", new ArrayList<Object>(
                Objects.requireNonNull(sourceOrder, "sourceOrder")
                        .components()));
        value.put("externalOrderPolicyIdentity",
                externalOrderPolicyIdentity);
        return identity(Constructor.EXTERNAL_CAUSE, value);
    }

    /** Constructs an exact admission-cause identity, preserving both nulls. */
    String admissionCauseIdentity(
            AdmissionKind admissionKind,
            String label,
            String triggeringEventBlueId,
            String parentTransitionIdentity,
            String policyIdentity) {
        LinkedHashMap<String, Object> value = objectValue();
        value.put("admissionKind",
                Objects.requireNonNull(admissionKind, "admissionKind").name());
        value.put("label", label);
        value.put("triggeringEventBlueId", triggeringEventBlueId);
        value.put("parentTransitionIdentity", parentTransitionIdentity);
        value.put("policyIdentity", policyIdentity);
        return identity(Constructor.ADMISSION_CAUSE, value);
    }

    /** Constructs the source-revision receipt nested by managed cause. */
    String sourceRevisionReceiptIdentity(
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            String originalSourceCauseIdentity) {
        LinkedHashMap<String, Object> value = sourceRevisionValue(
                childDocumentId, fromEpoch, toEpoch, beforeBlueId,
                afterBlueId, originalSourceCauseIdentity);
        return identity(Constructor.SOURCE_REVISION_RECEIPT, value);
    }

    /** Constructs an exact managed-revision cause identity. */
    String managedRevisionCauseIdentity(
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            String originalSourceCauseIdentity,
            String sourceRevisionReceiptIdentity) {
        LinkedHashMap<String, Object> value = objectValue();
        value.put("targetOccurrenceIdentity", targetOccurrenceIdentity);
        value.putAll(sourceRevisionValue(childDocumentId, fromEpoch, toEpoch,
                beforeBlueId, afterBlueId, originalSourceCauseIdentity));
        value.put("sourceRevisionReceiptIdentity",
                sourceRevisionReceiptIdentity);
        return identity(Constructor.MANAGED_REVISION_CAUSE, value);
    }

    /** Constructs the execution-policy identity from typed policy evidence. */
    String executionPolicyIdentity(ExecutionPolicy policy) {
        ExecutionPolicy selected = Objects.requireNonNull(policy, "policy");
        ArrayList<Object> limits = new ArrayList<Object>();
        for (Map.Entry<DocumentId, Long> entry
                : selected.localLimits().entrySet()) {
            LinkedHashMap<String, Object> item = objectValue();
            item.put("documentId", entry.getKey().value());
            item.put("limit", entry.getValue());
            limits.add(item);
        }
        LinkedHashMap<String, Object> value = objectValue();
        value.put("sharedLimit", Long.valueOf(selected.sharedLimit()));
        value.put("localLimits", limits);
        value.put("label", selected.label());
        return identity(Constructor.EXECUTION_POLICY, value);
    }

    /** Constructs the exact closed admission-candidate identity. */
    String admissionCandidateIdentity(AdmissionCandidate candidate) {
        AdmissionCandidate selected = Objects.requireNonNull(
                candidate, "candidate");
        LinkedHashMap<String, Object> evidence = objectValue();
        switch (selected.kind()) {
            case BAD_CYCLIC_PROOF:
                AdmissionCandidate.CandidateCyclicProof proof =
                        ((AdmissionCandidate.BadCyclicProof) selected)
                                .candidateCyclicProof();
                LinkedHashMap<String, Object> proofValue = objectValue();
                proofValue.put("componentIdentity", proof.componentIdentity());
                proofValue.put("masterBlueId", proof.masterBlueId());
                proofValue.put("memberStates", candidateMemberStates(
                        proof.memberStates()));
                proofValue.put("declaredPlaceholderSet", proofWireValues(
                        proof.declaredPlaceholderSet()));
                evidence.put("candidateCyclicProof", proofValue);
                break;
            case AMBIGUOUS_PRELIMINARY_MEMBERS:
                ArrayList<Object> members = new ArrayList<Object>();
                for (AdmissionCandidate.CandidateCyclicMember member
                        : ((AdmissionCandidate.AmbiguousPreliminaryMembers)
                        selected).candidateCyclicMembers()) {
                    LinkedHashMap<String, Object> memberValue = objectValue();
                    memberValue.put("documentId", member.documentId().value());
                    memberValue.put("document", NodeWireForm.get(
                            member.document(), NodeWireForm.Strategy.SIMPLE));
                    members.add(memberValue);
                }
                evidence.put("candidateCyclicMembers", members);
                break;
            case INVALID_OCCURRENCE_BINDING:
                ArrayList<Object> bindings = new ArrayList<Object>();
                for (AdmissionCandidate.CandidateOccurrenceBinding binding
                        : ((AdmissionCandidate.InvalidOccurrenceBinding)
                        selected).candidateOccurrenceBindings()) {
                    LinkedHashMap<String, Object> bindingValue = objectValue();
                    bindingValue.put("occurrenceIdentity",
                            binding.occurrenceIdentity());
                    bindingValue.put("bindingIdentity",
                            binding.bindingIdentity());
                    bindingValue.put("bindingPolicyIdentity",
                            binding.bindingPolicyIdentity());
                    bindingValue.put("sourceDocumentId",
                            binding.sourceDocumentId().value());
                    bindingValue.put("sourcePath", binding.sourcePath());
                    bindingValue.put("activationGeneration", Long.valueOf(
                            binding.activationGeneration()));
                    bindingValue.put("targetDocumentId",
                            binding.targetDocumentId().value());
                    bindingValue.put("expectedTargetBlueId",
                            binding.expectedTargetBlueId());
                    bindingValue.put("active",
                            Boolean.valueOf(binding.active()));
                    bindings.add(bindingValue);
                }
                evidence.put("candidateOccurrenceBindings", bindings);
                break;
            default:
                throw new AssertionError(
                        "Unhandled admission candidate " + selected.kind());
        }
        LinkedHashMap<String, Object> value = objectValue();
        value.put("kind", selected.kind().name());
        value.put("evidence", evidence);
        return identity(Constructor.ADMISSION_CANDIDATE, value);
    }

    /** Reconstructs the sole typed invocation cause identity. */
    String causeIdentity(ProcessingCause cause) {
        ProcessingCause selected = Objects.requireNonNull(cause, "cause");
        switch (selected.kind()) {
            case EXTERNAL:
                ExternalEventCause external = (ExternalEventCause) selected;
                return externalCauseIdentity(
                        external.eventBlueId(),
                        external.sourceOrder(),
                        external.externalOrderPolicyIdentity());
            case ADMISSION:
                AdmissionCause admission = (AdmissionCause) selected;
                return admissionCauseIdentity(
                        admission.admissionKind(),
                        admission.label(),
                        admission.triggeringEventBlueId(),
                        admission.parentTransitionIdentity(),
                        admission.policyIdentity());
            case MANAGED_REVISION:
                ManagedRevisionCause revision =
                        (ManagedRevisionCause) selected;
                return managedRevisionCauseIdentity(
                        revision.targetOccurrenceIdentity(),
                        revision.childDocumentId(),
                        revision.fromEpoch(),
                        revision.toEpoch(),
                        revision.beforeBlueId(),
                        revision.afterBlueId(),
                        revision.originalSourceCauseIdentity(),
                        revision.sourceRevisionReceiptIdentity());
            default:
                throw new AssertionError("Unhandled cause " + selected.kind());
        }
    }

    /** Constructs the complete Contracts 1.0 invocation identity. */
    String invocationIdentity(ClosureInvocationInput input) {
        return identity(Constructor.INVOCATION,
                invocationIdentityConstructorValue(input));
    }

    /** Returns the exact closed constructor value used by invocation identity. */
    Map<String, Object> invocationIdentityConstructorValue(
            ClosureInvocationInput input) {
        ClosureInvocationInput selected = Objects.requireNonNull(
                input, "input");
        AffectedClosureSnapshot snapshot = selected.snapshot();
        ClosureEnvironment environment = selected.environment();
        LinkedHashMap<String, Object> value = objectValue();
        value.put("operation", selected.operation().wireValue());
        value.put("causeIdentity", selected.cause().causeIdentity());
        value.put("admissionCandidateIdentity",
                selected.admissionCandidateIdentity());
        value.put("inputGraphGeneration", Long.valueOf(
                snapshot.graphGeneration()));
        value.put("inputClosureIdentity", snapshot.closureIdentity());
        value.put("documents", documentValues(snapshot.managedDocuments()));
        value.put("directDeliverySnapshotIdentity",
                selected.directDeliverySnapshotIdentity());
        value.put("occurrenceBindingSetIdentity",
                snapshot.occurrenceBindingSetIdentity());
        value.put("runtimeRegistryIdentity",
                environment.runtimeRegistryIdentity());
        value.put("gasPolicyIdentity", selected.executionPolicy().identity());
        value.put("cyclicFinalizerIdentity",
                environment.cyclicFinalizerIdentity());
        value.put("cyclicProofVerifierIdentity",
                environment.cyclicProofVerifierIdentity());
        value.put("blueLanguageSpecificationIdentity",
                environment.blueLanguageSpecificationIdentity());
        value.put("contractsSpecificationIdentity",
                environment.contractsSpecificationIdentity());
        value.put("managedDocumentIdentityPolicyIdentity",
                environment.managedDocumentIdentityPolicyIdentity());
        value.put("managedBindingPolicyIdentity",
                environment.managedBindingPolicyIdentity());
        value.put("exactNodeProviderDomainIdentity",
                environment.exactNodeProviderDomainIdentity());
        value.put("externalOrderPolicyIdentity",
                environment.externalOrderPolicyIdentity());
        value.put("gasManifestIdentity",
                environment.gasManifestIdentity());
        value.put("portableLimitPolicyIdentity",
                environment.portableLimitPolicyIdentity());
        return value;
    }

    /** Constructs one direct-delivery identity. */
    String directDeliveryIdentity(DirectLogicalDelivery delivery) {
        DirectLogicalDelivery selected = Objects.requireNonNull(
                delivery, "delivery");
        LinkedHashMap<String, Object> value = objectValue();
        value.put("targetDocumentId", selected.targetDocumentId().value());
        value.put("scopePath", selected.targetScope().address().path());
        value.put("activationGeneration", Long.valueOf(
                selected.targetScope().address().activationGeneration()));
        value.put("channelKey", selected.channelKey());
        value.put("logicalDeliveryKey", selected.logicalDeliveryKey());
        value.put("rawOccurrenceOrder",
                Long.valueOf(selected.rawOccurrenceOrder()));
        return identity(Constructor.DIRECT_DELIVERY, value);
    }

    /** Constructs a canonical direct-delivery snapshot identity. */
    String directDeliverySnapshotIdentity(
            List<DirectLogicalDelivery> deliveries) {
        ArrayList<DirectLogicalDelivery> ordered = new ArrayList<
                DirectLogicalDelivery>(Objects.requireNonNull(
                        deliveries, "deliveries"));
        Collections.sort(ordered);
        ArrayList<Object> identities = new ArrayList<Object>();
        Set<String> unique = new HashSet<String>();
        for (DirectLogicalDelivery delivery : ordered) {
            String identity = directDeliveryIdentity(
                    Objects.requireNonNull(delivery, "delivery"));
            if (!unique.add(identity)) {
                throw new IllegalArgumentException(
                        "Duplicate direct delivery identity");
            }
            identities.add(identity);
        }
        return identity(Constructor.DIRECT_DELIVERY_SNAPSHOT, identities);
    }

    /** Constructs canonical complete occurrence-binding set identity. */
    String occurrenceBindingSetIdentity(
            List<ManagedOccurrenceBinding> occurrences) {
        ArrayList<ManagedOccurrenceBinding> ordered = new ArrayList<
                ManagedOccurrenceBinding>(Objects.requireNonNull(
                        occurrences, "occurrences"));
        Collections.sort(ordered);
        ArrayList<Object> values = new ArrayList<Object>();
        for (ManagedOccurrenceBinding occurrence : ordered) {
            ManagedOccurrenceBinding selected = Objects.requireNonNull(
                    occurrence, "occurrence");
            LinkedHashMap<String, Object> value = objectValue();
            value.put("occurrenceIdentity", selected.occurrenceIdentity());
            value.put("bindingIdentity", selected.bindingIdentity());
            value.put("active", Boolean.valueOf(selected.active()));
            value.put("pendingHistoricalEpoch",
                    selected.pendingHistoricalEpoch());
            values.add(value);
        }
        return identity(Constructor.OCCURRENCE_BINDING_SET, values);
    }

    /** Reconstructs durable affected-closure identity from typed state. */
    String affectedClosureIdentity(AffectedClosureSnapshot snapshot) {
        AffectedClosureSnapshot selected = Objects.requireNonNull(
                snapshot, "snapshot");
        ArrayList<Object> documents = documentValues(
                selected.managedDocuments());
        String bindingSet = occurrenceBindingSetIdentity(
                selected.occurrences());
        requireClaim("occurrenceBindingSetIdentity",
                selected.occurrenceBindingSetIdentity(), bindingSet);
        ArrayList<String> componentStates = new ArrayList<String>();
        for (ComponentSnapshot component : selected.components()) {
            requireClaim("componentIdentity", component.componentIdentity(),
                    componentIdentity(component));
            if (component.kind() == ComponentKind.CYCLIC) {
                requireClaim("cyclicProofIdentity",
                        component.cyclicProofIdentity(),
                        cyclicProofIdentity(component));
            }
            requireClaim("componentStateIdentity",
                    component.componentStateIdentity(),
                    componentStateIdentity(component));
            componentStates.add(component.componentStateIdentity());
        }
        Collections.sort(componentStates);
        ArrayList<Object> publicRoots = new ArrayList<Object>();
        for (DocumentId root : selected.publicRootDocumentIds()) {
            publicRoots.add(root.value());
        }
        LinkedHashMap<String, Object> value = objectValue();
        value.put("graphGeneration", Long.valueOf(
                selected.graphGeneration()));
        value.put("documents", documents);
        value.put("occurrenceBindingSetIdentity", bindingSet);
        value.put("components", componentStates);
        value.put("publicRootDocumentIds", publicRoots);
        return identity(Constructor.AFFECTED_CLOSURE, value);
    }

    /** Constructs one event occurrence identity. */
    String eventOccurrenceIdentity(
            String invocationIdentity,
            long eventOccurrenceOrdinal,
            String eventBlueId) {
        LinkedHashMap<String, Object> value = objectValue();
        value.put("invocationIdentity", invocationIdentity);
        value.put("eventOccurrenceOrdinal",
                Long.valueOf(eventOccurrenceOrdinal));
        value.put("eventBlueId", eventBlueId);
        return identity(Constructor.EVENT_OCCURRENCE, value);
    }

    /** Constructs one queued work occurrence identity. */
    String workOccurrenceIdentity(
            String invocationIdentity,
            long workOrdinal,
            WorkKind workKind,
            String targetManagedScopeIdentity,
            String sourceOccurrenceIdentity) {
        LinkedHashMap<String, Object> value = objectValue();
        value.put("invocationIdentity", invocationIdentity);
        value.put("workOrdinal", Long.valueOf(workOrdinal));
        value.put("workKind", Objects.requireNonNull(
                workKind, "workKind").name());
        value.put("targetManagedScopeIdentity",
                targetManagedScopeIdentity);
        value.put("sourceOccurrenceIdentity", sourceOccurrenceIdentity);
        return identity(Constructor.WORK_OCCURRENCE, value);
    }

    /**
     * Constructs the exceptional direct BlueId checkpoint-domain identity.
     * Optional empty fields must already be omitted.
     */
    String checkpointDomainBlueId(Map<String, ?> checkpointDomain) {
        Object admitted = copyPortableValue(checkpointDomain,
                "checkpointDomain");
        Map<String, Object> value = requireObject(admitted,
                "checkpointDomain");
        Set<String> allowed = new HashSet<String>(Arrays.asList(
                "contractsVersion", "effectiveTypeBlueId",
                "sourceContributionNodeBlueIds",
                "deterministicDependencyNodeBlueIds",
                "runtimeDiscriminator"));
        if (!allowed.equals(union(value.keySet(), allowed))
                || !value.containsKey("contractsVersion")
                || !value.containsKey("effectiveTypeBlueId")
                || !value.containsKey("sourceContributionNodeBlueIds")) {
            throw new IllegalArgumentException(
                    "Checkpoint domain has an invalid field set");
        }
        if (!"1.0".equals(value.get("contractsVersion"))) {
            throw new IllegalArgumentException(
                    "contractsVersion must be exactly 1.0");
        }
        requireText(value, "effectiveTypeBlueId");
        requireTextArray(value.get("sourceContributionNodeBlueIds"),
                "sourceContributionNodeBlueIds", false);
        if (value.containsKey("deterministicDependencyNodeBlueIds")) {
            requireTextArray(value.get("deterministicDependencyNodeBlueIds"),
                    "deterministicDependencyNodeBlueIds", true);
        }
        if (value.containsKey("runtimeDiscriminator")) {
            requireNonEmptyText(value, "runtimeDiscriminator");
        }
        return DirectBlueIdCalculator.INSTANCE
                .directBlueIdFromCanonicalInput(value);
    }

    private static void validateConstructor(
            Constructor constructor, Object value) {
        if (!constructor.fields.isEmpty()) {
            requireExactFields(requireObject(value, OBJECT_VALUE),
                    constructor.fields);
        } else if (!(value instanceof List)) {
            throw new IllegalArgumentException(
                    constructor + " value must be an array");
        }
        switch (constructor) {
            case MANAGED_DOCUMENT_IDENTITY_POLICY:
            case MANAGED_BINDING_POLICY:
            case ADMISSION_POLICY:
            case EXACT_NODE_PROVIDER_DOMAIN:
            case EXTERNAL_ORDER_POLICY:
                requireNonEmptyText(
                        requireObject(value, OBJECT_VALUE), "label");
                return;
            case MANAGED_OCCURRENCE:
            case MANAGED_OCCURRENCE_BINDING:
                validateOccurrenceValue(requireObject(value, OBJECT_VALUE),
                        constructor == Constructor.MANAGED_OCCURRENCE_BINDING);
                return;
            case MANAGED_SCOPE_KEY:
                validateScopeValue(requireObject(value, OBJECT_VALUE));
                return;
            case COMPONENT:
                validateComponentValue(requireObject(value, OBJECT_VALUE));
                return;
            case CYCLIC_PROOF:
                validateCyclicProofValue(
                        requireObject(value, OBJECT_VALUE));
                return;
            case COMPONENT_STATE:
                validateComponentStateValue(
                        requireObject(value, OBJECT_VALUE));
                return;
            case PORTABLE_LIMIT_POLICY:
                validateNamedLimits(requireObject(value, OBJECT_VALUE),
                        "limits", "name", OBJECT_VALUE);
                return;
            case ADMISSION_CAUSE:
                validateAdmissionCause(requireObject(value, OBJECT_VALUE));
                return;
            case SOURCE_REVISION_RECEIPT:
                validateRevision(
                        requireObject(value, OBJECT_VALUE), false);
                return;
            case MANAGED_REVISION_CAUSE:
                validateRevision(requireObject(value, OBJECT_VALUE), true);
                return;
            case EXTERNAL_CAUSE:
                validateExternalCause(requireObject(value, OBJECT_VALUE));
                return;
            case EXECUTION_POLICY:
                validateExecutionPolicy(
                        requireObject(value, OBJECT_VALUE));
                return;
            case DIRECT_DELIVERY:
                validateDirectDelivery(requireObject(value, OBJECT_VALUE));
                return;
            case DIRECT_DELIVERY_SNAPSHOT:
                // These identities preserve the already-frozen direct
                // delivery order.  Digest lexical order is unrelated to the
                // normative raw-occurrence/target/scope/channel key order.
                validateIdentityArray(value, "directDeliveryIdentities", false);
                return;
            case OCCURRENCE_BINDING_SET:
                validateBindingSet(value);
                return;
            case AFFECTED_CLOSURE:
                validateClosure(requireObject(value, OBJECT_VALUE));
                return;
            case INVOCATION:
                validateInvocation(requireObject(value, OBJECT_VALUE));
                return;
            case TRANSITION_OCCURRENCE:
                validateTransition(requireObject(value, OBJECT_VALUE));
                return;
            case EVENT_OCCURRENCE:
                validateEventOccurrence(
                        requireObject(value, OBJECT_VALUE));
                return;
            case WORK_OCCURRENCE:
                validateWorkOccurrence(requireObject(value, OBJECT_VALUE));
                return;
            case CHANNEL_OCCURRENCE:
                validateChannelOccurrence(
                        requireObject(value, OBJECT_VALUE));
                return;
            case SUBSCRIPTION:
                validateSubscription(requireObject(value, OBJECT_VALUE));
                return;
            case GRAPH_CHANGES:
                validateOrdinalArray(value, "graphChangeOrdinal",
                        "graph changes");
                return;
            case CHECKPOINT_WRITES:
                validateOrdinalArray(value, "checkpointWriteOrdinal",
                        "checkpoint writes");
                return;
            case SUBSCRIPTION_DELTAS:
                validateOrdinalArray(value, "subscriptionDeltaOrdinal",
                        "subscription deltas");
                return;
            case PUBLIC_EVENTS:
                validateOrdinalArray(value, "publicEventOrdinal",
                        "public events");
                return;
            case REJECTED_CHARGE:
                validateRejectedCharge(requireObject(value, OBJECT_VALUE));
                return;
            case GAS_TRACE:
                requireArray(value, "gasTrace");
                return;
            case ADMISSION_CANDIDATE:
                validateAdmissionCandidate(
                        requireObject(value, OBJECT_VALUE));
                return;
            case PLATFORM_COMMIT_COMPANION:
                // Their closed top-level field sets are enforced above.  The
                // semantic verifier validates their closed nested unions and
                // cross-identity references before this construction step.
                return;
            default:
                throw new AssertionError("Unhandled constructor " + constructor);
        }
    }

    private static void validateOccurrenceValue(
            Map<String, Object> value, boolean binding) {
        requireNonEmptyText(value, "sourceDocumentId");
        requireAbsolutePointer(value, "sourcePath");
        long generation = requireSafeInteger(value, "activationGeneration");
        if (generation == 0L || "/".equals(value.get("sourcePath"))) {
            throw new IllegalArgumentException(
                    "Managed occurrence generation must be positive and non-Root");
        }
        requireNonEmptyText(value, "targetDocumentId");
        if (binding) {
            requireNonEmptyText(value, "expectedTargetBlueId");
        }
        requireSha256(value, "bindingPolicyIdentity", false);
    }

    private static void validateScopeValue(Map<String, Object> value) {
        requireNonEmptyText(value, "documentId");
        requireAbsolutePointer(value, "scopePath");
        long generation = requireSafeInteger(value, "activationGeneration");
        if ((generation == 0L) != "/".equals(value.get("scopePath"))) {
            throw new IllegalArgumentException(
                    "Only Root uses generation zero");
        }
    }

    private static void validateComponentValue(Map<String, Object> value) {
        String kind = requireNonEmptyText(value, "kind");
        if (!"ACYCLIC".equals(kind) && !"CYCLIC".equals(kind)) {
            throw new IllegalArgumentException("Invalid component kind");
        }
        requireSafeInteger(value, "generation");
        List<Object> members = requireTextArray(value.get("members"),
                "members", true);
        requireStrictTextOrder(members, "members");
        if ("ACYCLIC".equals(kind) && members.size() != 1) {
            throw new IllegalArgumentException(
                    "Acyclic component must have exactly one member");
        }
    }

    private static void validateCyclicProofValue(Map<String, Object> value) {
        requireSha256(value, "componentIdentity", false);
        requireNonEmptyText(value, "masterBlueId");
        validateMemberStates(value.get("memberStates"), true);
        if (!(value.get("declaredPlaceholderSet") instanceof List)) {
            throw new IllegalArgumentException(
                    "declaredPlaceholderSet must be an array");
        }
    }

    private static void validateComponentStateValue(
            Map<String, Object> value) {
        requireSha256(value, "componentIdentity", false);
        validateMemberStates(value.get("memberStates"), true);
        boolean masterNull = value.get("masterBlueId") == null;
        boolean proofNull = value.get("cyclicProofIdentity") == null;
        if (masterNull != proofNull) {
            throw new IllegalArgumentException(
                    "Master and cyclic proof identity must be null together");
        }
        if (!masterNull) {
            requireNonEmptyText(value, "masterBlueId");
            requireSha256(value, "cyclicProofIdentity", false);
        }
    }

    private static void validateNamedLimits(
            Map<String, Object> value,
            String arrayField,
            String nameField,
            String valueField) {
        requireNonEmptyText(value, "label");
        List<Object> limits = requireArray(value.get(arrayField), arrayField);
        String previous = null;
        for (Object itemValue : limits) {
            Map<String, Object> item = requireObject(itemValue, arrayField);
            requireExactFields(item, Arrays.asList(nameField, valueField));
            String name = requireNonEmptyText(item, nameField);
            requireSafeInteger(item, valueField);
            if (previous != null
                    && ClosureValueSupport.comparePortableText(
                    previous, name) >= 0) {
                throw new IllegalArgumentException(
                        arrayField + " must be sorted by " + nameField);
            }
            previous = name;
        }
    }

    private static void validateAdmissionCause(Map<String, Object> value) {
        String kind = requireNonEmptyText(value, "admissionKind");
        try {
            AdmissionKind.valueOf(kind);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid admissionKind", exception);
        }
        requireNonEmptyText(value, "label");
        requireNullableText(value, "triggeringEventBlueId");
        requireSha256(value, "parentTransitionIdentity", true);
        requireSha256(value, "policyIdentity", false);
    }

    private static void validateAdmissionCandidate(
            Map<String, Object> value) {
        String kind = requireNonEmptyText(value, "kind");
        Map<String, Object> evidence = requireObject(
                value.get("evidence"), "evidence");
        if (AdmissionCandidate.Kind.BAD_CYCLIC_PROOF.name().equals(kind)) {
            requireExactFields(evidence,
                    Collections.singletonList("candidateCyclicProof"));
            Map<String, Object> proof = requireObject(
                    evidence.get("candidateCyclicProof"),
                    "candidateCyclicProof");
            requireExactFields(proof, Arrays.asList(
                    "componentIdentity", "masterBlueId", "memberStates",
                    "declaredPlaceholderSet"));
            requireSha256(proof, "componentIdentity", false);
            requireNonEmptyText(proof, "masterBlueId");
            validateMemberStates(proof.get("memberStates"), true);
            if (requireArray(proof.get("declaredPlaceholderSet"),
                    "declaredPlaceholderSet").isEmpty()) {
                throw new IllegalArgumentException(
                        "declaredPlaceholderSet must not be empty");
            }
            return;
        }
        if (AdmissionCandidate.Kind.AMBIGUOUS_PRELIMINARY_MEMBERS
                .name().equals(kind)) {
            requireExactFields(evidence,
                    Collections.singletonList("candidateCyclicMembers"));
            List<Object> members = requireArray(
                    evidence.get("candidateCyclicMembers"),
                    "candidateCyclicMembers");
            if (members.isEmpty()) {
                throw new IllegalArgumentException(
                        "candidateCyclicMembers must not be empty");
            }
            String previous = null;
            for (Object memberValue : members) {
                Map<String, Object> member = requireObject(
                        memberValue, "candidateCyclicMember");
                requireExactFields(member,
                        Arrays.asList("documentId", "document"));
                String documentId = requireNonEmptyText(
                        member, "documentId");
                if (member.get("document") == null) {
                    throw new IllegalArgumentException(
                            "candidate document must not be null");
                }
                if (previous != null && ClosureValueSupport.comparePortableText(
                        previous, documentId) >= 0) {
                    throw new IllegalArgumentException(
                            "candidateCyclicMembers are not canonical");
                }
                previous = documentId;
            }
            return;
        }
        if (AdmissionCandidate.Kind.INVALID_OCCURRENCE_BINDING
                .name().equals(kind)) {
            requireExactFields(evidence, Collections.singletonList(
                    "candidateOccurrenceBindings"));
            validateCandidateOccurrenceBindings(requireArray(
                    evidence.get("candidateOccurrenceBindings"),
                    "candidateOccurrenceBindings"));
            return;
        }
        throw new IllegalArgumentException(
                "Invalid admission candidate kind");
    }

    private static void validateCandidateOccurrenceBindings(
            List<Object> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "candidateOccurrenceBindings must not be empty");
        }
        String previousOccurrence = null;
        String previousBinding = null;
        for (Object rowValue : rows) {
            Map<String, Object> row = requireObject(
                    rowValue, "candidateOccurrenceBinding");
            requireExactFields(row, Arrays.asList(
                    "occurrenceIdentity", "bindingIdentity",
                    "bindingPolicyIdentity", "sourceDocumentId",
                    "sourcePath", "activationGeneration",
                    "targetDocumentId", "expectedTargetBlueId", "active"));
            String occurrence = requireSha256(
                    row, "occurrenceIdentity", false);
            String binding = requireSha256(row, "bindingIdentity", false);
            requireSha256(row, "bindingPolicyIdentity", false);
            requireNonEmptyText(row, "sourceDocumentId");
            if ("/".equals(requireAbsolutePointer(row, "sourcePath"))
                    || requireSafeInteger(row, "activationGeneration") == 0L) {
                throw new IllegalArgumentException(
                        "Candidate occurrence must be non-Root and positive");
            }
            requireNonEmptyText(row, "targetDocumentId");
            requireNonEmptyText(row, "expectedTargetBlueId");
            requireBoolean(row, "active");
            if (previousOccurrence != null) {
                int order = ClosureValueSupport.comparePortableText(
                        previousOccurrence, occurrence);
                if (order > 0 || order == 0
                        && ClosureValueSupport.comparePortableText(
                        previousBinding, binding) >= 0) {
                    throw new IllegalArgumentException(
                            "candidateOccurrenceBindings are not canonical");
                }
            }
            previousOccurrence = occurrence;
            previousBinding = binding;
        }
    }

    private static void validateRevision(
            Map<String, Object> value, boolean managedCause) {
        if (managedCause) {
            requireSha256(value, "targetOccurrenceIdentity", false);
            requireSha256(value, "sourceRevisionReceiptIdentity", false);
        }
        requireNonEmptyText(value, "childDocumentId");
        long from = requireSafeInteger(value, "fromEpoch");
        long to = requireSafeInteger(value, "toEpoch");
        if (from == ClosureValueSupport.MAX_SAFE_INTEGER || to != from + 1L) {
            throw new IllegalArgumentException("toEpoch must equal fromEpoch + 1");
        }
        requireNonEmptyText(value, "beforeBlueId");
        requireNonEmptyText(value, "afterBlueId");
        requireSha256(value, "originalSourceCauseIdentity", false);
    }

    private static void validateExternalCause(Map<String, Object> value) {
        requireNonEmptyText(value, "eventBlueId");
        List<Object> order = requireArray(value.get("sourceOrder"),
                "sourceOrder");
        for (Object component : order) {
            if (!(component instanceof String) && !(component instanceof Long)) {
                throw new IllegalArgumentException(
                        "sourceOrder accepts only Integer or Text");
            }
        }
        requireSha256(value, "externalOrderPolicyIdentity", false);
    }

    private static void validateExecutionPolicy(Map<String, Object> value) {
        requireSafeInteger(value, "sharedLimit");
        requireNonEmptyText(value, "label");
        List<Object> limits = requireArray(value.get("localLimits"),
                "localLimits");
        String previous = null;
        for (Object itemValue : limits) {
            Map<String, Object> item = requireObject(itemValue, "localLimit");
            requireExactFields(item, Arrays.asList("documentId", "limit"));
            String documentId = requireNonEmptyText(item, "documentId");
            requireSafeInteger(item, "limit");
            if (previous != null && ClosureValueSupport.comparePortableText(
                    previous, documentId) >= 0) {
                throw new IllegalArgumentException(
                        "localLimits must be sorted by documentId");
            }
            previous = documentId;
        }
    }

    private static void validateDirectDelivery(Map<String, Object> value) {
        requireNonEmptyText(value, "targetDocumentId");
        requireAbsolutePointer(value, "scopePath");
        requireSafeInteger(value, "activationGeneration");
        requireNonEmptyText(value, "channelKey");
        requireNonEmptyText(value, "logicalDeliveryKey");
        requireSafeInteger(value, "rawOccurrenceOrder");
    }

    private static void validateBindingSet(Object value) {
        List<Object> items = requireArray(value, "occurrenceBindingSet");
        String previousOccurrence = null;
        String previousBinding = null;
        for (Object itemValue : items) {
            Map<String, Object> item = requireObject(itemValue, "binding");
            requireExactFields(item, Arrays.asList(
                    "occurrenceIdentity", "bindingIdentity", "active",
                    "pendingHistoricalEpoch"));
            String occurrence = requireSha256(item,
                    "occurrenceIdentity", false);
            String binding = requireSha256(item, "bindingIdentity", false);
            requireBoolean(item, "active");
            if (item.get("pendingHistoricalEpoch") != null) {
                requireSafeInteger(item, "pendingHistoricalEpoch");
            }
            if (previousOccurrence != null) {
                int order = ClosureValueSupport.comparePortableText(
                        previousOccurrence, occurrence);
                if (order > 0 || order == 0
                        && ClosureValueSupport.comparePortableText(
                        previousBinding, binding) >= 0) {
                    throw new IllegalArgumentException(
                            "Occurrence bindings are not canonical");
                }
            }
            previousOccurrence = occurrence;
            previousBinding = binding;
        }
    }

    private static void validateClosure(Map<String, Object> value) {
        requireSafeInteger(value, "graphGeneration");
        validateDocuments(value.get("documents"));
        requireSha256(value, "occurrenceBindingSetIdentity", false);
        validateIdentityArray(value.get("components"), "components", true);
        List<Object> roots = requireTextArray(value.get(
                "publicRootDocumentIds"), "publicRootDocumentIds", false);
        requireStrictTextOrder(roots, "publicRootDocumentIds");
    }

    private static void validateInvocation(Map<String, Object> value) {
        String operation = requireNonEmptyText(value, "operation");
        if (!"process-closure".equals(operation)
                && !"admit-closure".equals(operation)) {
            throw new IllegalArgumentException("Invalid closure operation");
        }
        requireSha256(value, "causeIdentity", false);
        requireSha256(value, "admissionCandidateIdentity", true);
        requireSafeInteger(value, "inputGraphGeneration");
        validateDocuments(value.get("documents"));
        for (String field : Arrays.asList(
                "inputClosureIdentity", "directDeliverySnapshotIdentity",
                "occurrenceBindingSetIdentity", "runtimeRegistryIdentity",
                "gasPolicyIdentity", "cyclicFinalizerIdentity",
                "cyclicProofVerifierIdentity",
                "blueLanguageSpecificationIdentity",
                "contractsSpecificationIdentity",
                "managedDocumentIdentityPolicyIdentity",
                "managedBindingPolicyIdentity",
                "exactNodeProviderDomainIdentity",
                "externalOrderPolicyIdentity", "gasManifestIdentity",
                "portableLimitPolicyIdentity")) {
            requireSha256(value, field, false);
        }
    }

    private static void validateTransition(Map<String, Object> value) {
        requireSha256(value, "invocationIdentity", false);
        requireSafeInteger(value, "transitionOrdinal");
        requireNonEmptyText(value, "targetDocumentId");
        requireNonEmptyText(value, "beforeBlueId");
        requireSha256(value, "causingWorkOccurrenceIdentity", false);
    }

    private static void validateEventOccurrence(Map<String, Object> value) {
        requireSha256(value, "invocationIdentity", false);
        requireSafeInteger(value, "eventOccurrenceOrdinal");
        requireNonEmptyText(value, "eventBlueId");
    }

    private static void validateWorkOccurrence(Map<String, Object> value) {
        requireSha256(value, "invocationIdentity", false);
        requireSafeInteger(value, "workOrdinal");
        String kind = requireNonEmptyText(value, "workKind");
        try {
            WorkKind.valueOf(kind);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid workKind", exception);
        }
        requireSha256(value, "targetManagedScopeIdentity", false);
        requireSha256(value, "sourceOccurrenceIdentity", false);
    }

    private static void validateChannelOccurrence(Map<String, Object> value) {
        requireNonEmptyText(value, "managedDocumentId");
        requireAbsolutePointer(value, "scopePath");
        requireSafeInteger(value, "scopeActivationGeneration");
        requireNonEmptyText(value, "rawChannelKey");
        requireNonEmptyText(value, "effectiveRuntimeContributionBlueId");
        requireNonEmptyText(value, "subscriptionHeaderBlueId");
    }

    private static void validateSubscription(Map<String, Object> value) {
        requireSha256(value, "channelOccurrenceIdentity", false);
        requireNonEmptyText(value, "documentBlueId");
        requireSafeInteger(value, "graphGeneration");
        requireSafeInteger(value, "componentGeneration");
    }

    private static void validateRejectedCharge(Map<String, Object> value) {
        requireNonEmptyText(value, "namespace");
        requireNonEmptyText(value, "counter");
        for (String field : Arrays.asList(
                "quantity", "weight", "subtotal", "remainingBeforeCharge")) {
            requireSafeInteger(value, field);
        }
        requireObject(value.get("applicableCap"), "applicableCap");
        requireObject(value.get("owner"), "owner");
    }

    private static void validateDocuments(Object value) {
        List<Object> documents = requireArray(value, "documents");
        String previous = null;
        for (Object itemValue : documents) {
            Map<String, Object> item = requireObject(itemValue, "document");
            requireExactFields(item, Arrays.asList(
                    "documentId", OBJECT_BLUE_ID,
                    KEY_INITIALIZED, KEY_TERMINATED,
                    "publicRoot", "epoch", "componentGeneration"));
            String documentId = requireNonEmptyText(item, "documentId");
            requireNonEmptyText(item, OBJECT_BLUE_ID);
            requireBoolean(item, KEY_INITIALIZED);
            requireBoolean(item, KEY_TERMINATED);
            requireBoolean(item, "publicRoot");
            requireSafeInteger(item, "epoch");
            requireSafeInteger(item, "componentGeneration");
            if (previous != null && ClosureValueSupport.comparePortableText(
                    previous, documentId) >= 0) {
                throw new IllegalArgumentException(
                        "documents must be sorted by documentId");
            }
            previous = documentId;
        }
    }

    private static void validateMemberStates(
            Object value, boolean requireDocumentOrder) {
        List<Object> states = requireArray(value, "memberStates");
        if (states.isEmpty()) {
            throw new IllegalArgumentException("memberStates must not be empty");
        }
        String previous = null;
        for (Object stateValue : states) {
            Map<String, Object> state = requireObject(
                    stateValue, "memberState");
            requireExactFields(
                    state, Arrays.asList("documentId", OBJECT_BLUE_ID));
            String documentId = requireNonEmptyText(state, "documentId");
            requireNonEmptyText(state, OBJECT_BLUE_ID);
            if (requireDocumentOrder && previous != null
                    && ClosureValueSupport.comparePortableText(
                    previous, documentId) >= 0) {
                throw new IllegalArgumentException(
                        "memberStates must be sorted by documentId");
            }
            previous = documentId;
        }
    }

    private static void validateIdentityArray(
            Object value, String field, boolean sorted) {
        List<Object> identities = requireTextArray(value, field, false);
        String previous = null;
        Set<String> unique = new HashSet<String>();
        for (Object item : identities) {
            String identity = ClosureValueSupport.requireSha256Identity(
                    (String) item, field + " item");
            if (!unique.add(identity)) {
                throw new IllegalArgumentException(field + " contains a duplicate");
            }
            if (sorted && previous != null && previous.compareTo(identity) >= 0) {
                throw new IllegalArgumentException(field + " is not sorted");
            }
            previous = identity;
        }
    }

    private static void validateOrdinalArray(
            Object value, String ordinalField, String label) {
        List<Object> items = requireArray(value, label);
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = requireObject(items.get(index), label);
            long ordinal = requireSafeInteger(item, ordinalField);
            if (ordinal != index) {
                throw new IllegalArgumentException(
                        label + " ordinals must be contiguous");
            }
        }
    }

    private static Object copyPortableValue(Object value, String field) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String) {
            return ClosureValueSupport.requirePortableText(
                    (String) value, field);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            long integer = ((Number) value).longValue();
            return Long.valueOf(ClosureValueSupport.requireSafeInteger(
                    integer, field));
        }
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(
                    ClosureValueSupport.MAX_SAFE_INTEGER)) > 0) {
                throw new IllegalArgumentException(
                        field + " must be a non-negative safe integer");
            }
            return Long.valueOf(integer.longValue());
        }
        if (value instanceof List) {
            ArrayList<Object> copy = new ArrayList<Object>();
            int index = 0;
            for (Object item : (List<?>) value) {
                copy.add(copyPortableValue(item,
                        field + "[" + index++ + "]"));
            }
            return copy;
        }
        if (value instanceof Map) {
            LinkedHashMap<String, Object> copy = objectValue();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException(
                            field + " has a non-Text object key");
                }
                String key = ClosureValueSupport.requirePortableText(
                        (String) entry.getKey(), field + " key");
                copy.put(key, copyPortableValue(entry.getValue(),
                        field + "." + key));
            }
            return copy;
        }
        throw new IllegalArgumentException(
                field + " has unsupported identity value type: "
                        + value.getClass().getName());
    }

    private static byte[] canonicalBytes(Object value) {
        try {
            return new JsonCanonicalizer(
                    IDENTITY_MAPPER.writeValueAsString(value))
                    .getEncodedUTF8();
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unable to canonicalize Contracts identity value",
                    exception);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static ObjectMapper identityMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        return mapper;
    }

    private static LinkedHashMap<String, Object> objectValue() {
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(
            Object value, String field) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireArray(Object value, String field) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return (List<Object>) value;
    }

    private static void requireExactFields(
            Map<String, Object> value, List<String> fields) {
        Set<String> expected = new HashSet<String>(fields);
        if (!expected.equals(value.keySet())) {
            throw new IllegalArgumentException(
                    "Expected exactly " + fields + "; got " + value.keySet());
        }
    }

    private static String requireNonEmptyText(
            Map<String, Object> value, String field) {
        Object item = value.get(field);
        if (!(item instanceof String)) {
            throw new IllegalArgumentException(field + " must be Text");
        }
        return ClosureValueSupport.requireNonEmptyText((String) item, field);
    }

    private static String requireText(
            Map<String, Object> value, String field) {
        Object item = value.get(field);
        if (!(item instanceof String)) {
            throw new IllegalArgumentException(field + " must be Text");
        }
        return ClosureValueSupport.requirePortableText((String) item, field);
    }

    private static void requireNullableText(
            Map<String, Object> value, String field) {
        if (value.get(field) != null) {
            requireNonEmptyText(value, field);
        }
    }

    private static long requireSafeInteger(
            Map<String, Object> value, String field) {
        Object item = value.get(field);
        if (!(item instanceof Long)) {
            throw new IllegalArgumentException(
                    field + " must be a JSON safe integer");
        }
        return ClosureValueSupport.requireSafeInteger(
                ((Long) item).longValue(), field);
    }

    private static boolean requireBoolean(
            Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof Boolean)) {
            throw new IllegalArgumentException(field + " must be Boolean");
        }
        return ((Boolean) value.get(field)).booleanValue();
    }

    private static String requireSha256(
            Map<String, Object> value, String field, boolean nullable) {
        Object item = value.get(field);
        if (item == null && nullable) {
            return null;
        }
        if (!(item instanceof String)) {
            throw new IllegalArgumentException(
                    field + " must be a sha256 identity");
        }
        return ClosureValueSupport.requireSha256Identity(
                (String) item, field);
    }

    private static String requireAbsolutePointer(
            Map<String, Object> value, String field) {
        Object item = value.get(field);
        if (!(item instanceof String)) {
            throw new IllegalArgumentException(field + " must be Text");
        }
        return ClosureValueSupport.requireAbsolutePointer(
                (String) item, field);
    }

    private static List<Object> requireTextArray(
            Object value, String field, boolean nonEmpty) {
        List<Object> items = requireArray(value, field);
        if (nonEmpty && items.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        for (Object item : items) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(
                        field + " items must be Text");
            }
            ClosureValueSupport.requireNonEmptyText((String) item,
                    field + " item");
        }
        return items;
    }

    private static void requireStrictTextOrder(
            List<Object> values, String field) {
        String previous = null;
        for (Object value : values) {
            String current = (String) value;
            if (previous != null && ClosureValueSupport.comparePortableText(
                    previous, current) >= 0) {
                throw new IllegalArgumentException(
                        field + " is not in canonical order");
            }
            previous = current;
        }
    }

    private static void requireEmbedded(ScopeAddress address) {
        if (Objects.requireNonNull(address, "sourceAddress").isRoot()) {
            throw new IllegalArgumentException(
                    "Managed occurrence address must be non-Root");
        }
    }

    private static List<Object> memberStates(ComponentSnapshot component) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (int index = 0;
                index < component.orderedMemberDocumentIds().size(); index++) {
            LinkedHashMap<String, Object> state = objectValue();
            state.put("documentId", component.orderedMemberDocumentIds()
                    .get(index).value());
            state.put(
                    OBJECT_BLUE_ID,
                    component.orderedMemberBlueIds().get(index));
            result.add(state);
        }
        return result;
    }

    private static List<Object> proofWireValue(CyclicSetProof proof) {
        return proofWireValues(Objects.requireNonNull(proof, "proof")
                .declaredPlaceholderSet());
    }

    private static List<Object> proofWireValues(List<Node> nodes) {
        ArrayList<Object> values = new ArrayList<Object>();
        for (Node node : Objects.requireNonNull(nodes, "nodes")) {
            values.add(NodeWireForm.get(
                    Objects.requireNonNull(node, "node"),
                    NodeWireForm.Strategy.SIMPLE));
        }
        return values;
    }

    private static ArrayList<Object> candidateMemberStates(
            List<AdmissionCandidate.CandidateMemberState> states) {
        ArrayList<Object> result = new ArrayList<Object>();
        for (AdmissionCandidate.CandidateMemberState state : states) {
            LinkedHashMap<String, Object> value = objectValue();
            value.put("documentId", state.documentId().value());
            value.put(OBJECT_BLUE_ID, state.blueId());
            result.add(value);
        }
        return result;
    }

    private static ArrayList<Object> documentValues(
            List<ManagedDocumentSnapshot> managedDocuments) {
        ArrayList<Object> documents = new ArrayList<Object>();
        for (ManagedDocumentSnapshot document : managedDocuments) {
            LinkedHashMap<String, Object> item = objectValue();
            item.put("documentId", document.documentId().value());
            item.put(OBJECT_BLUE_ID, document.blueId());
            item.put(
                    KEY_INITIALIZED,
                    Boolean.valueOf(document.initialized()));
            item.put(
                    KEY_TERMINATED,
                    Boolean.valueOf(document.terminated()));
            item.put("publicRoot", Boolean.valueOf(document.publicRoot()));
            item.put("epoch", Long.valueOf(document.epoch()));
            item.put("componentGeneration",
                    Long.valueOf(document.componentGeneration()));
            documents.add(item);
        }
        return documents;
    }

    private static LinkedHashMap<String, Object> sourceRevisionValue(
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            String originalSourceCauseIdentity) {
        LinkedHashMap<String, Object> value = objectValue();
        value.put("childDocumentId", Objects.requireNonNull(
                childDocumentId, "childDocumentId").value());
        value.put("fromEpoch", Long.valueOf(fromEpoch));
        value.put("toEpoch", Long.valueOf(toEpoch));
        value.put("beforeBlueId", beforeBlueId);
        value.put("afterBlueId", afterBlueId);
        value.put("originalSourceCauseIdentity",
                originalSourceCauseIdentity);
        return value;
    }

    private static void requireClaim(
            String field, String claimed, String calculated) {
        if (!Objects.equals(claimed, calculated)) {
            throw new IllegalArgumentException(field
                    + " mismatch: claimed=" + claimed
                    + ", calculated=" + calculated);
        }
    }

    private static Set<String> union(
            Set<String> left, Set<String> right) {
        HashSet<String> union = new HashSet<String>(left);
        union.addAll(right);
        return union;
    }
}
