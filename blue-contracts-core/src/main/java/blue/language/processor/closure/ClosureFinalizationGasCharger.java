package blue.language.processor.closure;

import blue.language.identity.BlueIdInputNormalizer;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalJsonValueWriter;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicMemberFinalization;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.identity.ScalarIdentityEncoder;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.GasChargeContext;
import blue.language.processor.ManagedSemanticGasBridge;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.provider.CyclicSetProof;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_DESCRIPTION;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_NAME;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE;

/**
 * Charges the normative Language identity work surrounding pure component
 * finalization on one managed-document session meter.
 *
 * <p>The frame freezes component-local admission before publication. The
 * finish half consumes only actual pure-kernel evidence and invocation-local
 * identity ledgers, charging each component boundary immediately before that
 * component's Language trace. The kernel has no externally visible effects;
 * a rejected boundary therefore publishes no derived state or evidence even
 * though the pure value was computed for verification. This class never reads
 * a fixture, expected trace, or projected result.</p>
 */
final class ClosureFinalizationGasCharger {

    private static final String PROCESSOR = "processor";
    private static final String TENTATIVE_COMPONENT_FINALIZATION =
            "tentativeComponentFinalization";
    private static final String CYCLIC_MEMBER_FINALIZED =
            "cyclicMemberFinalized";
    private static final ComponentCompletion NO_COMPONENT_COMPLETION =
            new ComponentCompletion() {
                @Override
                public void completed(
                        FinalizedComponentEvidence evidence) {
                    // Deliberately empty.
                }
            };

    private final DirectBlueIdCalculator identities =
            new DirectBlueIdCalculator();
    private final BlueIdInputNormalizer normalizer =
            new BlueIdInputNormalizer();
    private final ScalarIdentityEncoder scalarEncoder =
            new ScalarIdentityEncoder();

    private FinalizationFrame beginFinalization(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            ClosureWorkOccurrence owner,
            long firstFinalizationOrdinal,
            String reasonPrefix,
            List<List<DocumentId>> selectedCyclicComponents) {
        ManagedDocumentStepProcessor sharedMeter = Objects.requireNonNull(
                meter, "meter");
        ManagedDocumentGraph graph = Objects.requireNonNull(
                resultingGraph, "resultingGraph");
        String prefix = requireReasonPrefix(reasonPrefix);
        if (firstFinalizationOrdinal < 0L) {
            throw new IllegalArgumentException(
                    "firstFinalizationOrdinal must be non-negative");
        }
        Map<DocumentId, Long> generations = validateGenerations(
                graph, componentGenerations);
        ArrayList<ComponentFrame> cyclic = new ArrayList<ComponentFrame>();
        List<List<DocumentId>> selected = selectedCyclicComponents(
                graph, selectedCyclicComponents);
        int cyclicCount = selected.size();
        int cyclicOrdinal = 0;
        for (List<DocumentId> members : selected) {
            long generation = commonGeneration(members, generations);
            String componentPrefix = cyclicCount == 1
                    ? prefix
                    : prefix + ".component." + cyclicOrdinal;
            ComponentFrame component = new ComponentFrame(
                    members,
                    generation,
                    componentPrefix,
                    firstFinalizationOrdinal + cyclicOrdinal,
                    ClosureIdentityService.INSTANCE.componentIdentity(
                            ComponentKind.CYCLIC,
                            generation,
                            members));
            cyclic.add(component);
            cyclicOrdinal++;
        }
        return new FinalizationFrame(sharedMeter, owner, cyclic);
    }

    /**
     * Convenience admission using a stable implementation-owned reason.
     *
     * @param meter shared managed-document meter adapter
     * @param resultingGraph exact graph about to be finalized
     * @param componentGenerations resulting generation by lineage
     * @param owner owning work occurrence
     * @return single-use finalization frame
     */
    FinalizationFrame beginFinalization(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            ClosureWorkOccurrence owner) {
        Objects.requireNonNull(owner, "owner");
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                owner,
                0L,
                "work." + owner.ordinal() + ".finalization",
                null);
    }

    /**
     * Admits work-owned finalization using the next invocation-global
     * tentative-finalization ordinal.
     */
    FinalizationFrame beginFinalization(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            ClosureWorkOccurrence owner,
            long firstFinalizationOrdinal) {
        Objects.requireNonNull(owner, "owner");
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                owner,
                firstFinalizationOrdinal,
                "work." + owner.ordinal() + ".finalization",
                null);
    }

    /** Admits only exact cyclic components changed at this work boundary. */
    FinalizationFrame beginFinalization(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            ClosureWorkOccurrence owner,
            long firstFinalizationOrdinal,
            List<List<DocumentId>> changedCyclicComponents) {
        Objects.requireNonNull(owner, "owner");
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                owner,
                firstFinalizationOrdinal,
                "work." + owner.ordinal() + ".finalization",
                Objects.requireNonNull(
                        changedCyclicComponents,
                        "changedCyclicComponents"));
    }

    /**
     * Admits an invocation-owned post-quiescence checkpoint finalization.
     *
     * @param meter shared managed-document meter adapter
     * @param resultingGraph exact graph about to be finalized
     * @param componentGenerations resulting generation by lineage
     * @param settlementOrdinal invocation-global settlement ordinal
     * @return single-use finalization frame with no work occurrence owner
     */
    FinalizationFrame beginCheckpointSettlement(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            long settlementOrdinal) {
        if (settlementOrdinal < 0L) {
            throw new IllegalArgumentException(
                    "settlementOrdinal must be non-negative");
        }
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                null,
                0L,
                "checkpoint-settlement." + settlementOrdinal,
                null);
    }

    /** Admits one processor-owned post-initialization marker finalization. */
    FinalizationFrame beginInitializationBatch(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            long firstFinalizationOrdinal) {
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                null,
                firstFinalizationOrdinal,
                "initialization-batch",
                null);
    }

    /** Admits only exact cyclic components changed by marker installation. */
    FinalizationFrame beginInitializationBatch(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            long firstFinalizationOrdinal,
            List<List<DocumentId>> changedCyclicComponents) {
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                null,
                firstFinalizationOrdinal,
                "initialization-batch",
                Objects.requireNonNull(
                        changedCyclicComponents,
                        "changedCyclicComponents"));
    }

    /** Admits exact cyclic components changed by a terminated-marker write. */
    FinalizationFrame beginTerminationMarker(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            long afterWorkOrdinal,
            long firstFinalizationOrdinal,
            List<List<DocumentId>> changedCyclicComponents) {
        long causalOrdinal = ClosureValueSupport.requireSafeInteger(
                afterWorkOrdinal, "afterWorkOrdinal");
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                null,
                firstFinalizationOrdinal,
                "termination-marker.after-work." + causalOrdinal,
                Objects.requireNonNull(
                        changedCyclicComponents,
                        "changedCyclicComponents"));
    }

    /** Admits checkpoint finalization with its exact global ordinal. */
    FinalizationFrame beginCheckpointSettlement(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            long settlementOrdinal,
            long firstFinalizationOrdinal) {
        if (settlementOrdinal < 0L) {
            throw new IllegalArgumentException(
                    "settlementOrdinal must be non-negative");
        }
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                null,
                firstFinalizationOrdinal,
                "checkpoint-settlement." + settlementOrdinal,
                null);
    }

    /** Admits only exact cyclic components changed at checkpoint settlement. */
    FinalizationFrame beginCheckpointSettlement(
            ManagedDocumentStepProcessor meter,
            ManagedDocumentGraph resultingGraph,
            Map<DocumentId, Long> componentGenerations,
            long settlementOrdinal,
            long firstFinalizationOrdinal,
            List<List<DocumentId>> changedCyclicComponents) {
        if (settlementOrdinal < 0L) {
            throw new IllegalArgumentException(
                    "settlementOrdinal must be non-negative");
        }
        return beginFinalization(
                meter,
                resultingGraph,
                componentGenerations,
                null,
                firstFinalizationOrdinal,
                "checkpoint-settlement." + settlementOrdinal,
                Objects.requireNonNull(
                        changedCyclicComponents,
                        "changedCyclicComponents"));
    }

    /**
     * Collects exact pre-invocation descendant identities for no-double-charge
     * finalization accounting.
     *
     * <p>Managed document Root identities are deliberately excluded. Every
     * materialized descendant and pure reference is included using the same
     * canonical recursion as finalization charging.</p>
     *
     * @param exactInputBodies exact admitted document bodies
     * @return immutable descendant identity set
     */
    Set<String> existingIdentities(
            Map<DocumentId, Node> exactInputBodies) {
        TreeMap<DocumentId, Node> ordered =
                new TreeMap<DocumentId, Node>();
        for (Map.Entry<DocumentId, Node> entry
                : Objects.requireNonNull(
                        exactInputBodies, "exactInputBodies").entrySet()) {
            ordered.put(
                    Objects.requireNonNull(entry.getKey(), "documentId"),
                    Objects.requireNonNull(
                            entry.getValue(), "exact input body"));
        }
        LinkedHashSet<String> identities = new LinkedHashSet<String>();
        for (Node body : ordered.values()) {
            Object projected = normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput.get(body));
            collectExisting(projected, false, identities);
        }
        return Collections.unmodifiableSet(identities);
    }

    /**
     * Charges the authenticated historical successor carried by one managed
     * revision receipt without installing it as the child's current head.
     */
    String chargeManagedRevisionAfterDocument(
            ManagedDocumentStepProcessor meter,
            DocumentId childDocumentId,
            Node afterDocument,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        Node exact = Objects.requireNonNull(
                afterDocument, "afterDocument").clone();
        requireNoCyclicPlaceholder(
                exact, new IdentityHashMap<Object, Boolean>());
        Object projected = normalizer.normalizeCanonicalInput(
                NodeToBlueIdInput.get(exact));
        String reason = "admission.managed-revision.after-document";
        GasChargeContext attribution = GasChargeContext.closure(
                Objects.requireNonNull(
                        childDocumentId, "childDocumentId").value(),
                null,
                null,
                null,
                null,
                null,
                null,
                reason);
        return establishExactValue(
                projected,
                Objects.requireNonNull(meter, "meter"),
                Objects.requireNonNull(
                        establishedBlueIds, "establishedBlueIds"),
                Objects.requireNonNull(existingBlueIds, "existingBlueIds"),
                attribution,
                reason);
    }

    /**
     * Charges one retained successor and, for a cyclic member, its complete
     * historical proof through the ordinary Language identity formulas.
     *
     * <p>This is admission evidence rather than a new graph component.  The
     * resolved body always pays the established after-document charge.  A
     * cyclic proof additionally pays preliminary member, canonical member,
     * stable-sort, and master-fold identity work without emitting component
     * finalization ownership or receipts.</p>
     */
    String chargeManagedRevisionAfterDocument(
            ManagedDocumentStepProcessor meter,
            DocumentId childDocumentId,
            String expectedAfterBlueId,
            Node afterDocument,
            CyclicSetProof afterCyclicProof,
            long cyclicCanonicalBytesLimit,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        String expected = ClosureValueSupport.requireBlueId(
                expectedAfterBlueId, "expectedAfterBlueId");
        boolean cyclicAfter = BlueIds.hasCyclicMemberSeparator(expected);
        if (cyclicAfter) {
            BlueIds.requireBlueIdOrCyclicMember(
                    expected, "expectedAfterBlueId");
        }
        if (cyclicAfter != (afterCyclicProof != null)) {
            throw new IllegalArgumentException(
                    "A cyclic successor requires exactly one complete cyclic-set proof");
        }
        if (afterCyclicProof == null) {
            return chargeManagedRevisionAfterDocument(
                    meter,
                    childDocumentId,
                    afterDocument,
                    establishedBlueIds,
                    existingBlueIds);
        }
        CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                afterCyclicProof.declaredPlaceholderSet());
        ManagedRevisionCyclicEvidenceVerifier.verify(
                expected, afterDocument, proof);
        long limit = ClosureValueSupport.requireSafeInteger(
                cyclicCanonicalBytesLimit,
                "cyclicCanonicalBytesLimit");
        long canonicalBytes = new CyclicCanonicalLimitProjection()
                .canonicalBytesForProof(proof.declaredPlaceholderSet());
        if (canonicalBytes > limit) {
            throw ClosureAdmissionPortableLimits.exceeded(
                    "cyclicCanonicalBytesPerComponent",
                    canonicalBytes,
                    limit);
        }
        CyclicSetFinalization finalization =
                CircularSetIdentityCalculator
                        .calculateCircularSetFinalization(
                                proof.declaredPlaceholderSet());
        if (!BlueIds.cyclicSetMasterBlueId(expected).equals(
                finalization.masterBlueId())) {
            throw new IllegalArgumentException(
                    "Historical cyclic proof calculates another master BlueId");
        }

        chargeManagedRevisionAfterDocument(
                meter,
                childDocumentId,
                afterDocument,
                establishedBlueIds,
                existingBlueIds);
        chargeManagedRevisionCyclicProof(
                Objects.requireNonNull(meter, "meter"),
                Objects.requireNonNull(childDocumentId, "childDocumentId"),
                finalization,
                Objects.requireNonNull(
                        establishedBlueIds, "establishedBlueIds"),
                Objects.requireNonNull(existingBlueIds, "existingBlueIds"));
        return expected;
    }

    private void chargeManagedRevisionCyclicProof(
            ManagedDocumentStepProcessor meter,
            DocumentId childDocumentId,
            CyclicSetFinalization finalization,
            Set<String> established,
            Set<String> existing) {
        String prefix =
                "admission.managed-revision.after-document.cyclic-proof";
        GasChargeContext base = GasChargeContext.closure(
                childDocumentId.value(),
                null,
                null,
                null,
                null,
                null,
                null,
                prefix);
        ArrayList<SortMember> preliminary = new ArrayList<SortMember>();
        for (CyclicMemberFinalization member
                : finalization.membersInInputOrder()) {
            Node zeroed = zeroed(member.canonicalMemberBody());
            Object projected = normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput
                            .getAllowingCyclicPlaceholders(zeroed));
            byte[] canonicalInput = CanonicalJsonValueWriter.write(projected);
            if (!Arrays.equals(
                    canonicalInput,
                    member.preliminaryCanonicalInputBytes())) {
                throw new IllegalStateException(
                        "Historical ZERO-form input differs from Language evidence");
            }
            String reason = prefix + ".preliminary."
                    + member.inputIndex();
            String preliminaryBlueId = establishExactNode(
                    zeroed,
                    projected,
                    meter,
                    established,
                    existing,
                    withReason(base, reason),
                    reason);
            if (!member.preliminaryBlueId().equals(preliminaryBlueId)) {
                throw new IllegalStateException(
                        "Metered historical preliminary identity differs from Language evidence");
            }
            preliminary.add(new SortMember(
                    member,
                    preliminaryBlueId,
                    new String(canonicalInput, StandardCharsets.UTF_8)));
        }

        List<SortMember> sorted = stableSortHistorical(
                preliminary, meter, base, prefix);
        List<CyclicMemberFinalization> canonicalMembers =
                finalization.membersInCanonicalOrder();
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index).member != canonicalMembers.get(index)) {
                throw new IllegalStateException(
                        "Metered historical preliminary order differs from Language evidence");
            }
        }

        ArrayList<Object> masterElements = new ArrayList<Object>();
        for (CyclicMemberFinalization member : canonicalMembers) {
            Node canonicalBody = member.canonicalMemberBody();
            Object projected = normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput.getAllowingCyclicPlaceholders(
                            canonicalBody));
            String reason = prefix + ".canonical-member."
                    + member.canonicalIndex();
            establishExactNode(
                    canonicalBody,
                    projected,
                    meter,
                    established,
                    existing,
                    withReason(base, reason),
                    reason);
            masterElements.add(normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput
                            .getListElementAllowingCyclicPlaceholders(
                                    canonicalBody,
                                    member.canonicalIndex())));
        }
        Object masterInput = normalizer.normalizeCanonicalInput(
                masterElements);
        if (!Arrays.equals(
                CanonicalJsonValueWriter.write(masterInput),
                finalization.canonicalIdentityInputBytes())) {
            throw new IllegalStateException(
                    "Historical master input differs from Language evidence");
        }
        String masterReason = prefix + ".master";
        String master = establishExactValue(
                masterInput,
                meter,
                established,
                existing,
                withReason(base, masterReason),
                masterReason);
        if (!finalization.masterBlueId().equals(master)) {
            throw new IllegalStateException(
                    "Metered historical master identity differs from Language evidence");
        }
    }

    /**
     * Charges actual cyclic Language evidence and member-finalization work.
     *
     * @param frame admitted pre-kernel frame
     * @param result actual pure-kernel result
     * @param establishedBlueIds mutable invocation-local new-identity ledger
     * @param existingBlueIds immutable identities established before invocation
     */
    void finishFinalization(
            FinalizationFrame frame,
            ComponentFinalizationResult result,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        finishFinalization(
                frame,
                Collections.<DocumentId, Node>emptyMap(),
                result,
                establishedBlueIds,
                existingBlueIds,
                false,
                NO_COMPONENT_COMPLETION);
    }

    /**
     * Charges actual cyclic Language evidence after checking source-body
     * coverage captured at the pre-finalization boundary.
     *
     * <p>Canonical placeholder bodies are taken from the unchanged Language
     * result, because raw local bodies still contain materialized managed
     * references. Source bodies remain required completeness evidence and are
     * never used as a fixture projection.</p>
     *
     * @param frame admitted pre-kernel frame
     * @param sourceBodies complete pre-finalizer local-body snapshot
     * @param result actual pure-kernel result
     * @param establishedBlueIds mutable invocation-local new-identity ledger
     * @param existingBlueIds identities established before this invocation
     */
    void finishFinalization(
            FinalizationFrame frame,
            Map<DocumentId, Node> sourceBodies,
            ComponentFinalizationResult result,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        finishFinalization(
                frame,
                sourceBodies,
                result,
                establishedBlueIds,
                existingBlueIds,
                true,
                NO_COMPONENT_COMPLETION);
    }

    /**
     * Charges selected components and publishes completion after each whole
     * component trace, before admission of the next component boundary.
     */
    void finishFinalization(
            FinalizationFrame frame,
            Map<DocumentId, Node> sourceBodies,
            ComponentFinalizationResult result,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds,
            ComponentCompletion completion) {
        finishFinalization(
                frame,
                sourceBodies,
                result,
                establishedBlueIds,
                existingBlueIds,
                true,
                Objects.requireNonNull(completion, "completion"));
    }

    /** Original-seed identity memo selection; budget union must not merge these ledgers. */
    interface IdentityLedgerSelector { IdentityLedger forDocument(DocumentId document); }

    static final class IdentityLedger {
        final Set<String> established;
        final Set<String> existing;
        IdentityLedger(Set<String> established, Set<String> existing) {
            this.established = Objects.requireNonNull(established, "established");
            this.existing = Objects.requireNonNull(existing, "existing");
        }
    }

    /**
     * Per-member Language work uses that member's original seed. Component-wide
     * boundary, sort and master work use the first intrinsic component member,
     * irrespective of the runtime which happened to initiate the accepted union.
     */
    void finishFinalization(FinalizationFrame frame, Map<DocumentId, Node> sourceBodies,
            ComponentFinalizationResult result, IdentityLedgerSelector ledgers, ComponentCompletion completion) {
        finishFinalization(frame, sourceBodies, result, Objects.requireNonNull(ledgers, "ledgers"),
                true, true, Objects.requireNonNull(completion, "completion"));
    }

    /**
     * Charges one exact changed acyclic document or containing spine.
     *
     * @param meter shared managed-document meter adapter
     * @param documentId independently managed document owner
     * @param exactBody exact changed body
     * @param componentGeneration exact resulting component generation
     * @param owner work occurrence owning the reconstruction
     * @param establishedBlueIds mutable invocation-local new-identity ledger
     * @param existingBlueIds identities established before this invocation
     * @return exact direct body BlueId
     */
    String chargeAcyclicChangedBody(
            ManagedDocumentStepProcessor meter,
            DocumentId documentId,
            Node exactBody,
            long componentGeneration,
            ClosureWorkOccurrence owner,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        ClosureWorkOccurrence work = Objects.requireNonNull(owner, "owner");
        return chargeAcyclicChangedBody(
                meter,
                documentId,
                exactBody,
                componentGeneration,
                work,
                "work." + work.ordinal() + ".acyclic-finalization",
                establishedBlueIds,
                existingBlueIds);
    }

    /**
     * Charges one invocation-owned acyclic checkpoint reconstruction.
     *
     * @param meter shared managed-document meter adapter
     * @param documentId independently managed document owner
     * @param exactBody exact changed body
     * @param componentGeneration exact resulting component generation
     * @param settlementOrdinal invocation-global settlement ordinal
     * @param establishedBlueIds mutable invocation-local new-identity ledger
     * @param existingBlueIds identities established before this invocation
     * @return exact direct body BlueId
     */
    String chargeCheckpointAcyclicChangedBody(
            ManagedDocumentStepProcessor meter,
            DocumentId documentId,
            Node exactBody,
            long componentGeneration,
            long settlementOrdinal,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        if (settlementOrdinal < 0L) {
            throw new IllegalArgumentException(
                    "settlementOrdinal must be non-negative");
        }
        return chargeAcyclicChangedBody(
                meter,
                documentId,
                exactBody,
                componentGeneration,
                null,
                "checkpoint-settlement." + settlementOrdinal
                        + ".acyclic-finalization",
                establishedBlueIds,
                existingBlueIds);
    }

    /** Charges one marker-batch-owned changed acyclic Root. */
    String chargeInitializationAcyclicChangedBody(
            ManagedDocumentStepProcessor meter,
            DocumentId documentId,
            Node exactBody,
            long componentGeneration,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        return chargeAcyclicChangedBody(
                meter,
                documentId,
                exactBody,
                componentGeneration,
                null,
                "initialization-batch.acyclic-finalization",
                establishedBlueIds,
                existingBlueIds);
    }

    /** Charges one terminated-marker-owned changed acyclic Root. */
    String chargeTerminationAcyclicChangedBody(
            ManagedDocumentStepProcessor meter,
            DocumentId documentId,
            Node exactBody,
            long componentGeneration,
            long afterWorkOrdinal,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        long causalOrdinal = ClosureValueSupport.requireSafeInteger(
                afterWorkOrdinal, "afterWorkOrdinal");
        return chargeAcyclicChangedBody(
                meter,
                documentId,
                exactBody,
                componentGeneration,
                null,
                "termination-marker.after-work." + causalOrdinal
                        + ".acyclic-finalization",
                establishedBlueIds,
                existingBlueIds);
    }

    String chargeAcyclicChangedBody(
            ManagedDocumentStepProcessor meter,
            DocumentId documentId,
            Node exactBody,
            long componentGeneration,
            ClosureWorkOccurrence owner,
            String reasonPrefix,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds) {
        Node body = Objects.requireNonNull(exactBody, "exactBody").clone();
        requireNoCyclicPlaceholder(
                body,
                new IdentityHashMap<Object, Boolean>());
        GasChargeContext attribution = context(
                Objects.requireNonNull(documentId, "documentId"),
                componentGeneration,
                owner,
                requireReasonPrefix(reasonPrefix));
        Object projected = normalizer.normalizeCanonicalInput(
                NodeToBlueIdInput.get(body));
        String blueId = establishExactValue(
                projected,
                Objects.requireNonNull(meter, "meter"),
                Objects.requireNonNull(
                        establishedBlueIds, "establishedBlueIds"),
                Objects.requireNonNull(existingBlueIds, "existingBlueIds"),
                attribution,
                reasonPrefix);
        if (!(projected instanceof List)
                && !isPureReference(projected)) {
            long expected = NodeCanonicalizer
                    .directIdentityCanonicalSize(body);
            long actual = directCanonicalInputSize(projected);
            if (expected != actual) {
                throw new IllegalStateException(
                        "Acyclic identity gas input differs from Node canonicalizer");
            }
        }
        return blueId;
    }

    private void finishFinalization(
            FinalizationFrame frame,
            Map<DocumentId, Node> sourceBodies,
            ComponentFinalizationResult result,
            Set<String> establishedBlueIds,
            Set<String> existingBlueIds,
            boolean requireSourceCoverage,
            ComponentCompletion completion) {
        final IdentityLedger shared = new IdentityLedger(establishedBlueIds, existingBlueIds);
        finishFinalization(frame, sourceBodies, result, document -> shared,
                false, requireSourceCoverage, completion);
    }

    private void finishFinalization(FinalizationFrame frame, Map<DocumentId, Node> sourceBodies,
            ComponentFinalizationResult result, IdentityLedgerSelector selector,
            boolean seedOwned, boolean requireSourceCoverage, ComponentCompletion completion) {
        FinalizationFrame admitted = Objects.requireNonNull(frame, "frame");
        if (admitted.finished) {
            throw new IllegalStateException(
                    "Finalization gas frame was already consumed");
        }
        ComponentFinalizationResult finalized = Objects.requireNonNull(
                result, "result");
        Map<DocumentId, IdentityLedger> ledgers = new HashMap<DocumentId, IdentityLedger>();
        for (ComponentFrame component : admitted.components) {
            for (DocumentId member : component.members)
                ledgers.put(member, Objects.requireNonNull(selector.forDocument(member), "member identity ledger"));
            component.gasOwner = seedOwned ? component.members.get(0) : null;
        }
        Map<List<DocumentId>, FinalizedComponentEvidence> evidenceByMembers =
                evidenceByMembers(finalized);
        if (requireSourceCoverage) {
            requireSourceCoverage(admitted, sourceBodies);
        }
        for (ComponentFrame component : admitted.components) {
            FinalizedComponentEvidence evidence = evidenceByMembers.get(
                    component.members);
            if (evidence == null
                    || evidence.component().componentGeneration()
                    != component.generation
                    || evidence.cyclicFinalization() == null) {
                throw new IllegalArgumentException(
                        "Finalization result does not match the admitted cyclic frame");
            }
            admitted.meter.charge(
                    PROCESSOR,
                    TENTATIVE_COMPONENT_FINALIZATION,
                    1L,
                    finalizationContext(
                            null,
                            component,
                            admitted.owner,
                            component.reasonPrefix
                                    + ".finalization-boundary"));
            chargeCyclicComponent(
                    admitted,
                    component,
                    evidence,
                    ledgers);
            completion.completed(evidence);
        }
        admitted.finished = true;
    }

    private void chargeCyclicComponent(
            FinalizationFrame frame,
            ComponentFrame component,
            FinalizedComponentEvidence evidence,
            Map<DocumentId, IdentityLedger> ledgers) {
        CyclicSetFinalization finalization = evidence.cyclicFinalization();
        ArrayList<SortMember> preliminary = new ArrayList<SortMember>();
        for (CyclicMemberFinalization member
                : finalization.membersInInputOrder()) {
            DocumentId documentId = component.members.get(
                    member.inputIndex());
            Node zeroed = zeroed(member.canonicalMemberBody());
            Object projected = normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput
                            .getAllowingCyclicPlaceholders(zeroed));
            byte[] canonicalInput = CanonicalJsonValueWriter.write(projected);
            if (!Arrays.equals(
                    canonicalInput,
                    member.preliminaryCanonicalInputBytes())) {
                throw new IllegalStateException(
                        "ZERO-form canonical input differs from Language evidence");
            }
            String preliminaryBlueId = establishExactNode(
                    zeroed,
                    projected,
                    frame.meter,
                    ledgers.get(documentId).established,
                    ledgers.get(documentId).existing,
                    finalizationContext(
                            documentId,
                            component,
                            frame.owner,
                            component.reasonPrefix + ".preliminary."
                                    + member.inputIndex()),
                    component.reasonPrefix + ".preliminary."
                            + member.inputIndex());
            if (!member.preliminaryBlueId().equals(preliminaryBlueId)) {
                throw new IllegalStateException(
                        "Charged preliminary identity differs from Language evidence");
            }
            preliminary.add(new SortMember(
                    member,
                    preliminaryBlueId,
                    new String(canonicalInput, StandardCharsets.UTF_8)));
        }

        List<SortMember> sorted = stableSort(
                preliminary, frame, component);
        List<CyclicMemberFinalization> canonicalMembers =
                finalization.membersInCanonicalOrder();
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index).member != canonicalMembers.get(index)) {
                throw new IllegalStateException(
                        "Metered preliminary order differs from Language evidence");
            }
        }

        ArrayList<Object> masterElements = new ArrayList<Object>();
        for (CyclicMemberFinalization member : canonicalMembers) {
            DocumentId documentId = component.members.get(
                    member.inputIndex());
            Node canonicalBody = member.canonicalMemberBody();
            Object projected = normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput.getAllowingCyclicPlaceholders(
                            canonicalBody));
            establishExactNode(
                    canonicalBody,
                    projected,
                    frame.meter,
                    ledgers.get(documentId).established,
                    ledgers.get(documentId).existing,
                    finalizationContext(
                            documentId,
                            component,
                            frame.owner,
                            component.reasonPrefix + ".canonical-member."
                                    + member.canonicalIndex()),
                    component.reasonPrefix + ".canonical-member."
                            + member.canonicalIndex());
            masterElements.add(normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput
                            .getListElementAllowingCyclicPlaceholders(
                                    canonicalBody,
                                    member.canonicalIndex())));
        }
        Object masterInput = normalizer.normalizeCanonicalInput(
                masterElements);
        if (!Arrays.equals(
                CanonicalJsonValueWriter.write(masterInput),
                finalization.canonicalIdentityInputBytes())) {
            throw new IllegalStateException(
                    "Master canonical input differs from Language evidence");
        }
        String master = establishExactValue(
                masterInput,
                frame.meter,
                ledgers.get(component.members.get(0)).established,
                ledgers.get(component.members.get(0)).existing,
                finalizationContext(
                        null,
                        component,
                        frame.owner,
                        component.reasonPrefix + ".master"),
                component.reasonPrefix + ".master");
        if (!finalization.masterBlueId().equals(master)) {
            throw new IllegalStateException(
                    "Charged master identity differs from Language evidence");
        }

        for (CyclicMemberFinalization member : canonicalMembers) {
            DocumentId documentId = component.members.get(
                    member.inputIndex());
            frame.meter.charge(
                    PROCESSOR,
                    CYCLIC_MEMBER_FINALIZED,
                    1L,
                    finalizationContext(
                            documentId,
                            component,
                            frame.owner,
                            component.reasonPrefix + ".member-finalized."
                                    + member.canonicalIndex()));
        }
    }

    private List<SortMember> stableSort(
            List<SortMember> input,
            FinalizationFrame frame,
            ComponentFrame component) {
        if (input.size() < 2) {
            return Collections.unmodifiableList(
                    new ArrayList<SortMember>(input));
        }
        ArrayList<SortMember> source = new ArrayList<SortMember>(input);
        ArrayList<SortMember> target = new ArrayList<SortMember>(
                Collections.nCopies(input.size(), (SortMember) null));
        long comparisonOrdinal = 0L;
        for (int width = 1;
                width < source.size();
                width = width > source.size() / 2
                        ? source.size() : width * 2) {
            for (int start = 0;
                    start < source.size();
                    start += width * 2) {
                int middle = Math.min(start + width, source.size());
                int end = Math.min(start + width * 2, source.size());
                int left = start;
                int right = middle;
                int output = start;
                while (left < middle && right < end) {
                    SortMember leftMember = source.get(left);
                    SortMember rightMember = source.get(right);
                    long ordinal = comparisonOrdinal++;
                    frame.meter.semanticGas(finalizationContext(
                            null,
                            component,
                            frame.owner,
                            component.reasonPrefix + ".preliminary-sort."
                                    + ordinal)).sortComparisons(1L);
                    int compared = compareToken(
                            leftMember.preliminaryBlueId,
                            rightMember.preliminaryBlueId,
                            frame,
                            component,
                            component.reasonPrefix
                                    + ".preliminary-compare." + ordinal,
                            component.reasonPrefix
                                    + ".preliminary-text." + ordinal);
                    if (compared == 0) {
                        compared = compareToken(
                                leftMember.canonicalInput,
                                rightMember.canonicalInput,
                                frame,
                                component,
                                component.reasonPrefix
                                        + ".preliminary-canonical-compare."
                                        + ordinal,
                                component.reasonPrefix
                                        + ".preliminary-canonical-text."
                                        + ordinal);
                    }
                    if (compared == 0) {
                        throw new IllegalArgumentException(
                                "Indistinguishable preliminary cyclic members");
                    }
                    if (compared <= 0) {
                        target.set(output++, source.get(left++));
                    } else {
                        target.set(output++, source.get(right++));
                    }
                }
                while (left < middle) {
                    target.set(output++, source.get(left++));
                }
                while (right < end) {
                    target.set(output++, source.get(right++));
                }
            }
            ArrayList<SortMember> swap = source;
            source = target;
            target = swap;
        }
        return Collections.unmodifiableList(
                new ArrayList<SortMember>(source));
    }

    private List<SortMember> stableSortHistorical(
            List<SortMember> input,
            ManagedDocumentStepProcessor meter,
            GasChargeContext base,
            String prefix) {
        if (input.size() < 2) {
            return Collections.unmodifiableList(
                    new ArrayList<SortMember>(input));
        }
        ArrayList<SortMember> source = new ArrayList<SortMember>(input);
        ArrayList<SortMember> target = new ArrayList<SortMember>(
                Collections.nCopies(input.size(), (SortMember) null));
        long comparisonOrdinal = 0L;
        for (int width = 1;
                width < source.size();
                width = width > source.size() / 2
                        ? source.size() : width * 2) {
            for (int start = 0;
                    start < source.size();
                    start += width * 2) {
                int middle = Math.min(start + width, source.size());
                int end = Math.min(start + width * 2, source.size());
                int left = start;
                int right = middle;
                int output = start;
                while (left < middle && right < end) {
                    SortMember leftMember = source.get(left);
                    SortMember rightMember = source.get(right);
                    long ordinal = comparisonOrdinal++;
                    meter.semanticGas(withReason(
                            base,
                            prefix + ".preliminary-sort." + ordinal))
                            .sortComparisons(1L);
                    int compared = compareHistoricalToken(
                            leftMember.preliminaryBlueId,
                            rightMember.preliminaryBlueId,
                            meter,
                            base,
                            prefix + ".preliminary-compare." + ordinal,
                            prefix + ".preliminary-text." + ordinal);
                    if (compared == 0) {
                        compared = compareHistoricalToken(
                                leftMember.canonicalInput,
                                rightMember.canonicalInput,
                                meter,
                                base,
                                prefix + ".preliminary-canonical-compare."
                                        + ordinal,
                                prefix + ".preliminary-canonical-text."
                                        + ordinal);
                    }
                    if (compared == 0) {
                        throw new IllegalArgumentException(
                                "Indistinguishable historical cyclic proof members");
                    }
                    if (compared <= 0) {
                        target.set(output++, source.get(left++));
                    } else {
                        target.set(output++, source.get(right++));
                    }
                }
                while (left < middle) {
                    target.set(output++, source.get(left++));
                }
                while (right < end) {
                    target.set(output++, source.get(right++));
                }
            }
            ArrayList<SortMember> swap = source;
            source = target;
            target = swap;
        }
        return Collections.unmodifiableList(
                new ArrayList<SortMember>(source));
    }

    private int compareToken(
            String left,
            String right,
            FinalizationFrame frame,
            ComponentFrame component,
            String comparisonReason,
            String textReason) {
        TextComparison comparison = compareCodePoints(left, right);
        frame.meter.semanticGas(finalizationContext(
                null,
                component,
                frame.owner,
                comparisonReason)).scalarComparisons(1L);
        frame.meter.semanticGas(finalizationContext(
                null,
                component,
                frame.owner,
                textReason)).textOperandsExamined(
                        comparison.codePointsRead,
                        2L);
        return comparison.result;
    }

    private int compareHistoricalToken(
            String left,
            String right,
            ManagedDocumentStepProcessor meter,
            GasChargeContext base,
            String comparisonReason,
            String textReason) {
        TextComparison comparison = compareCodePoints(left, right);
        meter.semanticGas(withReason(base, comparisonReason))
                .scalarComparisons(1L);
        meter.semanticGas(withReason(base, textReason))
                .textOperandsExamined(
                        comparison.codePointsRead,
                        2L);
        return comparison.result;
    }

    private String establishExactNode(
            Node node,
            Object projected,
            ManagedDocumentStepProcessor meter,
            Set<String> established,
            Set<String> existing,
            GasChargeContext attribution,
            String reasonPrefix) {
        if (!(projected instanceof List) && !isPureReference(projected)) {
            long expected = NodeCanonicalizer
                    .directIdentityCanonicalSizeAllowingCyclicPlaceholders(
                            node);
            long actual = directCanonicalInputSize(projected);
            if (expected != actual) {
                throw new IllegalStateException(
                        "Cyclic identity gas input differs from Node canonicalizer");
            }
        }
        return establishExactValue(
                projected,
                meter,
                established,
                existing,
                attribution,
                reasonPrefix);
    }

    private String establishExactValue(
            Object value,
            ManagedDocumentStepProcessor meter,
            Set<String> established,
            Set<String> existing,
            GasChargeContext attribution,
            String reasonPrefix) {
        IdentityFacts facts = facts(value);
        if (facts.pureReference
                || existing.contains(facts.blueId)
                || established.contains(facts.blueId)) {
            return facts.blueId;
        }
        for (int index = 0; index < facts.children.size(); index++) {
            establishExactValue(
                    facts.children.get(index),
                    meter,
                    established,
                    existing,
                    withReason(
                            attribution,
                            reasonPrefix + ".child." + index),
                    reasonPrefix + ".child." + index);
        }
        meter.semanticGas(withReason(
                attribution,
                reasonPrefix + ".node-established"))
                .nodeIdentitiesEstablished(1L);
        if (facts.list) {
            meter.semanticGas(withReason(
                    attribution,
                    reasonPrefix + ".list-fold"))
                    .fullListIdentity(facts.listLength);
        } else {
            meter.semanticGas(withReason(
                    attribution,
                    reasonPrefix + ".object-members"))
                    .objectMembersRebuilt(facts.directMemberCount);
            meter.semanticGas(withReason(
                    attribution,
                    reasonPrefix + ".direct-hash"))
                    .directIdentityInput(facts.canonicalInputBytes);
        }
        established.add(facts.blueId);
        return facts.blueId;
    }

    private IdentityFacts facts(Object supplied) {
        Object value = normalizer.normalizeCanonicalInput(supplied);
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return facts(scalarEncoder.encode(value));
        }
        String blueId = identities.directBlueIdFromCanonicalInput(value);
        if (isPureReference(value)) {
            return IdentityFacts.reference(blueId);
        }
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            return IdentityFacts.list(
                    blueId,
                    new ArrayList<Object>(values),
                    values.size());
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                    "Unsupported canonical identity value");
        }
        Map<String, Object> map = castMap(value);
        TreeMap<String, Object> ordered = new TreeMap<String, Object>(map);
        ArrayList<Object> children = new ArrayList<Object>();
        TreeMap<String, Object> contributions =
                new TreeMap<String, Object>();
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            if (isLiteralField(entry.getKey())) {
                contributions.put(entry.getKey(), entry.getValue());
            } else {
                Object child = entry.getValue();
                children.add(child);
                contributions.put(
                        entry.getKey(),
                        Collections.<String, Object>singletonMap(
                                OBJECT_BLUE_ID,
                                identities.directBlueIdFromCanonicalInput(
                                        normalizer.normalizeCanonicalInput(
                                                child))));
            }
        }
        return IdentityFacts.object(
                blueId,
                children,
                contributions.size(),
                CanonicalJsonValueWriter.write(contributions).length);
    }

    private void collectExisting(
            Object value,
            boolean includeSelf,
            Set<String> collected) {
        IdentityFacts direct = facts(value);
        if (direct.pureReference) {
            collected.add(direct.blueId);
            return;
        }
        if (includeSelf) {
            collected.add(direct.blueId);
        }
        for (Object child : direct.children) {
            collectExisting(child, true, collected);
        }
    }

    private long directCanonicalInputSize(Object projected) {
        IdentityFacts direct = facts(projected);
        return direct.canonicalInputBytes;
    }

    private static TextComparison compareCodePoints(
            String left,
            String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        long read = 0L;
        while (leftOffset < left.length()
                && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            read++;
            if (leftCodePoint != rightCodePoint) {
                return new TextComparison(
                        Integer.compare(leftCodePoint, rightCodePoint),
                        read);
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        return new TextComparison(
                Boolean.compare(
                        leftOffset < left.length(),
                        rightOffset < right.length()),
                read);
    }

    private static Map<DocumentId, Long> validateGenerations(
            ManagedDocumentGraph graph,
            Map<DocumentId, Long> supplied) {
        Map<DocumentId, Long> source = Objects.requireNonNull(
                supplied, "componentGenerations");
        if (!source.keySet().equals(
                new HashSet<DocumentId>(graph.documentIds()))) {
            throw new IllegalArgumentException(
                    "Component generations must cover the resulting graph");
        }
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        for (DocumentId documentId : graph.documentIds()) {
            Long generation = Objects.requireNonNull(
                    source.get(documentId), "component generation");
            if (generation.longValue() < 0L) {
                throw new IllegalArgumentException(
                        "Component generation must be non-negative");
            }
            result.put(documentId, generation);
        }
        return Collections.unmodifiableMap(result);
    }

    private static long commonGeneration(
            List<DocumentId> members,
            Map<DocumentId, Long> generations) {
        long generation = generations.get(members.get(0)).longValue();
        for (DocumentId member : members) {
            if (generations.get(member).longValue() != generation) {
                throw new IllegalArgumentException(
                        "One component has inconsistent generations");
            }
        }
        return generation;
    }

    /**
     * Selects cyclic components whose exact state differs from the preceding
     * tentative snapshot, preserving target-before-source kernel order.
     */
    static List<List<DocumentId>> changedCyclicComponents(
            List<ComponentSnapshot> precedingComponents,
            ComponentFinalizationResult finalized) {
        HashMap<List<DocumentId>, ComponentSnapshot> preceding =
                new HashMap<List<DocumentId>, ComponentSnapshot>();
        for (ComponentSnapshot component : Objects.requireNonNull(
                precedingComponents, "precedingComponents")) {
            List<DocumentId> members = component
                    .orderedMemberDocumentIds();
            if (preceding.put(members, component) != null) {
                throw new IllegalArgumentException(
                        "Preceding snapshot repeats a component member set");
            }
        }
        ArrayList<List<DocumentId>> changed =
                new ArrayList<List<DocumentId>>();
        for (FinalizedComponentEvidence evidence : Objects.requireNonNull(
                finalized, "finalized").components()) {
            ComponentSnapshot component = evidence.component();
            if (component.kind() != ComponentKind.CYCLIC) {
                continue;
            }
            List<DocumentId> members = component
                    .orderedMemberDocumentIds();
            ComponentSnapshot before = preceding.get(members);
            if (before == null
                    || before.kind() != ComponentKind.CYCLIC
                    || !before.componentStateIdentity().equals(
                            component.componentStateIdentity())) {
                changed.add(Collections.unmodifiableList(
                        new ArrayList<DocumentId>(members)));
            }
        }
        return Collections.unmodifiableList(changed);
    }

    /**
     * Builds and portable-limit-checks the one immutable component selection
     * used by gas charging and receipt publication.
     */
    static CyclicFinalizationPlan plan(
            List<ComponentSnapshot> precedingComponents,
            ComponentFinalizationResult finalized,
            long canonicalBytesLimit) {
        return plan(
                precedingComponents,
                finalized,
                canonicalBytesLimit,
                Collections.<List<DocumentId>>emptySet());
    }

    /** Builds a plan with additional component-local verification receipts. */
    static CyclicFinalizationPlan plan(
            List<ComponentSnapshot> precedingComponents,
            ComponentFinalizationResult finalized,
            long canonicalBytesLimit,
            Set<List<DocumentId>> requiredComponents) {
        long limit = ClosureValueSupport.requireSafeInteger(
                canonicalBytesLimit,
                "cyclic canonical bytes limit");
        List<List<DocumentId>> changed = changedCyclicComponents(
                precedingComponents, finalized);
        Set<List<DocumentId>> selected =
                new HashSet<List<DocumentId>>(changed);
        for (List<DocumentId> members : Objects.requireNonNull(
                requiredComponents, "requiredComponents")) {
            selected.add(Collections.unmodifiableList(
                    new ArrayList<DocumentId>(Objects.requireNonNull(
                            members, "required cyclic component"))));
        }
        ArrayList<List<DocumentId>> orderedSelection =
                new ArrayList<List<DocumentId>>();
        LinkedHashMap<List<DocumentId>, Long> canonicalBytes =
                new LinkedHashMap<List<DocumentId>, Long>();
        CyclicCanonicalLimitProjection projector =
                new CyclicCanonicalLimitProjection();
        for (FinalizedComponentEvidence evidence : finalized.components()) {
            List<DocumentId> members = evidence.component()
                    .orderedMemberDocumentIds();
            if (!selected.contains(members)) {
                continue;
            }
            List<DocumentId> selectedMembers =
                    Collections.unmodifiableList(
                            new ArrayList<DocumentId>(members));
            orderedSelection.add(selectedMembers);
            long bytes = projector.project(
                    Objects.requireNonNull(
                            evidence.cyclicFinalization(),
                            "selected cyclic finalization"))
                    .canonicalBytes();
            if (bytes > limit) {
                throw ClosureAdmissionPortableLimits.exceeded(
                        "cyclicCanonicalBytesPerComponent",
                        bytes,
                        limit);
            }
            canonicalBytes.put(
                    selectedMembers,
                    Long.valueOf(bytes));
        }
        if (canonicalBytes.size() != selected.size()) {
            throw new IllegalStateException(
                    "Changed cyclic selection lost finalization evidence");
        }
        return new CyclicFinalizationPlan(
                Collections.unmodifiableList(orderedSelection),
                canonicalBytes);
    }

    private static List<List<DocumentId>> selectedCyclicComponents(
            ManagedDocumentGraph graph,
            List<List<DocumentId>> supplied) {
        ArrayList<List<DocumentId>> available =
                new ArrayList<List<DocumentId>>();
        for (List<DocumentId> members
                : new SccPartitioner().partition(graph)) {
            if (members.size() > 1
                    || graph.hasSelfEdge(members.get(0))) {
                available.add(Collections.unmodifiableList(
                        new ArrayList<DocumentId>(members)));
            }
        }
        if (supplied == null) {
            return Collections.unmodifiableList(available);
        }
        HashSet<List<DocumentId>> requested =
                new HashSet<List<DocumentId>>();
        for (List<DocumentId> members : supplied) {
            List<DocumentId> copy = Collections.unmodifiableList(
                    new ArrayList<DocumentId>(Objects.requireNonNull(
                            members, "selected cyclic component")));
            if (!requested.add(copy)) {
                throw new IllegalArgumentException(
                        "Selected cyclic component is duplicated");
            }
        }
        ArrayList<List<DocumentId>> selected =
                new ArrayList<List<DocumentId>>();
        for (List<DocumentId> members : available) {
            if (requested.contains(members)) {
                selected.add(members);
            }
        }
        if (selected.size() != requested.size()) {
            throw new IllegalArgumentException(
                    "Selected finalization member set is not a resulting cyclic component");
        }
        return Collections.unmodifiableList(selected);
    }

    private static Map<List<DocumentId>, FinalizedComponentEvidence>
            evidenceByMembers(ComponentFinalizationResult result) {
        HashMap<List<DocumentId>, FinalizedComponentEvidence> indexed =
                new HashMap<List<DocumentId>, FinalizedComponentEvidence>();
        for (FinalizedComponentEvidence evidence : result.components()) {
            List<DocumentId> members = evidence.component()
                    .orderedMemberDocumentIds();
            if (indexed.put(members, evidence) != null) {
                throw new IllegalArgumentException(
                        "Duplicate component member set in finalization result");
            }
        }
        return indexed;
    }

    private static void requireSourceCoverage(
            FinalizationFrame frame,
            Map<DocumentId, Node> sourceBodies) {
        Map<DocumentId, Node> bodies = Objects.requireNonNull(
                sourceBodies, "sourceBodies");
        for (ComponentFrame component : frame.components) {
            for (DocumentId documentId : component.members) {
                Objects.requireNonNull(
                        bodies.get(documentId),
                        "source body for " + documentId.value());
            }
        }
    }

    private static GasChargeContext context(
            DocumentId documentId,
            long componentGeneration,
            ClosureWorkOccurrence owner,
            String reason) {
        return GasChargeContext.closure(
                documentId == null ? null : documentId.value(),
                null,
                null,
                Long.valueOf(componentGeneration),
                null,
                null,
                owner == null ? null : owner.workIdentity(),
                reason);
    }

    private static GasChargeContext finalizationContext(
            DocumentId documentId,
            ComponentFrame component,
            ClosureWorkOccurrence owner,
            String reason) {
        return context(
                documentId == null ? component.gasOwner : documentId,
                component.generation,
                owner,
                reason).withFinalizationOwner(
                        component.finalizationOrdinal,
                        component.componentIdentity,
                        component.generation);
    }

    private static GasChargeContext withReason(
            GasChargeContext source,
            String reason) {
        GasChargeContext context = GasChargeContext.closure(
                source.documentId(),
                source.scopePath(),
                source.activationGeneration(),
                source.componentGeneration(),
                source.contractKey(),
                source.logicalPath(),
                source.workOccurrenceId(),
                reason);
        if (source.finalizationOrdinal() != null) {
            context = context.withFinalizationOwner(
                    source.finalizationOrdinal().longValue(),
                    source.finalizationComponentIdentity(),
                    source.finalizationComponentGeneration().longValue());
        }
        return context;
    }

    private static String requireReasonPrefix(String value) {
        String prefix = Objects.requireNonNull(value, "reasonPrefix");
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException(
                    "reasonPrefix must not be empty");
        }
        return prefix;
    }

    private static boolean isLiteralField(String key) {
        return OBJECT_NAME.equals(key)
                || OBJECT_DESCRIPTION.equals(key)
                || OBJECT_VALUE.equals(key);
    }

    private static boolean isPureReference(Object value) {
        if (!(value instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        return map.size() == 1 && map.get(OBJECT_BLUE_ID) instanceof String;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Node zeroed(Node source) {
        Node result = Objects.requireNonNull(source, "source").clone();
        replaceCyclicPlaceholders(
                result,
                new IdentityHashMap<Object, Boolean>(),
                BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER);
        return result;
    }

    private static void requireNoCyclicPlaceholder(
            Node node,
            IdentityHashMap<Object, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (BlueIds.isCyclicCalculationPlaceholder(node.getBlueId())) {
            throw new IllegalArgumentException(
                    "Cyclic placeholder escaped into acyclic identity work");
        }
        requireNoCyclicPlaceholder(node.getType(), visited);
        requireNoCyclicPlaceholder(node.getItemType(), visited);
        requireNoCyclicPlaceholder(node.getKeyType(), visited);
        requireNoCyclicPlaceholder(node.getValueType(), visited);
        requireNoCyclicPlaceholder(node.getBlue(), visited);
        requireNoCyclicPlaceholder(node.getContracts(), visited);
        requireNoCyclicPlaceholder(node.getSchema(), visited);
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                requireNoCyclicPlaceholder(child, visited);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                requireNoCyclicPlaceholder(child, visited);
            }
        }
    }

    private static void requireNoCyclicPlaceholder(
            Schema schema,
            IdentityHashMap<Object, Boolean> visited) {
        if (schema == null || visited.put(schema, Boolean.TRUE) != null) {
            return;
        }
        if (BlueIds.isCyclicCalculationPlaceholder(schema.getBlueId())) {
            throw new IllegalArgumentException(
                    "Cyclic placeholder escaped into acyclic schema work");
        }
        for (Node child : schemaChildren(schema)) {
            requireNoCyclicPlaceholder(child, visited);
        }
    }

    private static void replaceCyclicPlaceholders(
            Node node,
            IdentityHashMap<Object, Boolean> visited,
            String replacement) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (BlueIds.isCyclicCalculationPlaceholder(node.getBlueId())) {
            node.blueId(replacement);
        }
        replaceCyclicPlaceholders(node.getType(), visited, replacement);
        replaceCyclicPlaceholders(node.getItemType(), visited, replacement);
        replaceCyclicPlaceholders(node.getKeyType(), visited, replacement);
        replaceCyclicPlaceholders(node.getValueType(), visited, replacement);
        replaceCyclicPlaceholders(node.getBlue(), visited, replacement);
        replaceCyclicPlaceholders(node.getContracts(), visited, replacement);
        replaceCyclicPlaceholders(node.getSchema(), visited, replacement);
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                replaceCyclicPlaceholders(child, visited, replacement);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                replaceCyclicPlaceholders(child, visited, replacement);
            }
        }
    }

    private static void replaceCyclicPlaceholders(
            Schema schema,
            IdentityHashMap<Object, Boolean> visited,
            String replacement) {
        if (schema == null || visited.put(schema, Boolean.TRUE) != null) {
            return;
        }
        if (BlueIds.isCyclicCalculationPlaceholder(schema.getBlueId())) {
            schema.blueId(replacement);
        }
        for (Node child : schemaChildren(schema)) {
            replaceCyclicPlaceholders(child, visited, replacement);
        }
    }

    private static List<Node> schemaChildren(Schema schema) {
        ArrayList<Node> children = new ArrayList<Node>();
        children.add(schema.getRequired());
        children.add(schema.getMinLength());
        children.add(schema.getMaxLength());
        children.add(schema.getMinimum());
        children.add(schema.getMaximum());
        children.add(schema.getExclusiveMinimum());
        children.add(schema.getExclusiveMaximum());
        children.add(schema.getMultipleOf());
        children.add(schema.getMinItems());
        children.add(schema.getMaxItems());
        children.add(schema.getUniqueItems());
        children.add(schema.getMinFields());
        children.add(schema.getMaxFields());
        if (schema.getEnum() != null) {
            children.addAll(schema.getEnum());
        }
        return children;
    }

    /** Called only after one complete component Language trace is admitted. */
    interface ComponentCompletion {
        void completed(FinalizedComponentEvidence evidence);
    }

    /** Preflighted exact cyclic selection for one tentative boundary. */
    static final class CyclicFinalizationPlan {

        private final List<List<DocumentId>> memberSets;
        private final Map<List<DocumentId>, Long> canonicalBytes;

        private CyclicFinalizationPlan(
                List<List<DocumentId>> memberSets,
                Map<List<DocumentId>, Long> canonicalBytes) {
            this.memberSets = memberSets;
            this.canonicalBytes = Collections.unmodifiableMap(
                    new LinkedHashMap<List<DocumentId>, Long>(
                            canonicalBytes));
        }

        List<List<DocumentId>> memberSets() {
            return memberSets;
        }

        int size() {
            return memberSets.size();
        }

        long canonicalBytes(FinalizedComponentEvidence evidence) {
            Long value = canonicalBytes.get(Objects.requireNonNull(
                    evidence, "evidence").component()
                    .orderedMemberDocumentIds());
            if (value == null) {
                throw new IllegalArgumentException(
                        "Completed component is outside the preflighted plan");
            }
            return value.longValue();
        }
    }

    /** Single-use split-finalization admission token. */
    static final class FinalizationFrame {

        private final ManagedDocumentStepProcessor meter;
        private final ClosureWorkOccurrence owner;
        private final List<ComponentFrame> components;
        private boolean finished;

        private FinalizationFrame(
                ManagedDocumentStepProcessor meter,
                ClosureWorkOccurrence owner,
                List<ComponentFrame> components) {
            this.meter = meter;
            this.owner = owner;
            this.components = Collections.unmodifiableList(
                    new ArrayList<ComponentFrame>(components));
        }
    }

    private static final class ComponentFrame {
        private DocumentId gasOwner;

        private final List<DocumentId> members;
        private final long generation;
        private final String reasonPrefix;
        private final long finalizationOrdinal;
        private final String componentIdentity;

        private ComponentFrame(
                List<DocumentId> members,
                long generation,
                String reasonPrefix,
                long finalizationOrdinal,
                String componentIdentity) {
            this.members = Collections.unmodifiableList(
                    new ArrayList<DocumentId>(members));
            this.generation = generation;
            this.reasonPrefix = reasonPrefix;
            this.finalizationOrdinal = finalizationOrdinal;
            this.componentIdentity = componentIdentity;
        }
    }

    private static final class SortMember {

        private final CyclicMemberFinalization member;
        private final String preliminaryBlueId;
        private final String canonicalInput;

        private SortMember(
                CyclicMemberFinalization member,
                String preliminaryBlueId,
                String canonicalInput) {
            this.member = member;
            this.preliminaryBlueId = preliminaryBlueId;
            this.canonicalInput = canonicalInput;
        }
    }

    private static final class TextComparison {

        private final int result;
        private final long codePointsRead;

        private TextComparison(int result, long codePointsRead) {
            this.result = result;
            this.codePointsRead = codePointsRead;
        }
    }

    private static final class IdentityFacts {

        private final String blueId;
        private final boolean pureReference;
        private final boolean list;
        private final List<Object> children;
        private final long directMemberCount;
        private final long canonicalInputBytes;
        private final long listLength;

        private IdentityFacts(
                String blueId,
                boolean pureReference,
                boolean list,
                List<Object> children,
                long directMemberCount,
                long canonicalInputBytes,
                long listLength) {
            this.blueId = blueId;
            this.pureReference = pureReference;
            this.list = list;
            this.children = Collections.unmodifiableList(
                    new ArrayList<Object>(children));
            this.directMemberCount = directMemberCount;
            this.canonicalInputBytes = canonicalInputBytes;
            this.listLength = listLength;
        }

        private static IdentityFacts reference(String blueId) {
            return new IdentityFacts(
                    blueId,
                    true,
                    false,
                    Collections.emptyList(),
                    0L,
                    0L,
                    0L);
        }

        private static IdentityFacts list(
                String blueId,
                List<Object> children,
                long length) {
            return new IdentityFacts(
                    blueId, false, true, children, 0L, 0L, length);
        }

        private static IdentityFacts object(
                String blueId,
                List<Object> children,
                long directMemberCount,
                long canonicalInputBytes) {
            return new IdentityFacts(
                    blueId,
                    false,
                    false,
                    children,
                    directMemberCount,
                    canonicalInputBytes,
                    0L);
        }
    }
}
