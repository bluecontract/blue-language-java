package blue.language.processor.closure;

import blue.language.identity.BlueIdReferenceValidator;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.NodePathEditor;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.VerifyingNodeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Authoritative semantic cross-checks over complete closure evidence. */
final class ClosureEvidenceVerifier {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private ClosureEvidenceVerifier() {
    }

    /**
     * Recomputes the graph, component partition, generations, Language
     * identities, proof, exact member bodies, and occurrence identities of a
     * purported authoritative snapshot.
     */
    static void verifySnapshot(AffectedClosureSnapshot snapshot) {
        AffectedClosureSnapshot selected = Objects.requireNonNull(
                snapshot, "snapshot");
        verifyFinalizedState(selected, selected, null);
        verifyMarkers(selected);
    }

    /** Reconciles every result receipt with its exact input and output state. */
    static void verifyTransition(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output,
            List<ResultingDocument> resultingDocuments,
            List<GraphChange> graphChanges,
            List<SubscriptionDelta> subscriptionDeltas,
            List<CheckpointWrite> checkpointWrites,
            List<PublicEventOccurrence> publicEvents,
            List<GasTraceEntry> gasTrace,
            ComponentFinalizationResult reusableFinalization) {
        verifyTransition(
                input,
                output,
                resultingDocuments,
                graphChanges,
                subscriptionDeltas,
                checkpointWrites,
                publicEvents,
                gasTrace,
                reusableFinalization,
                Collections.<DocumentId>emptySet(),
                Collections.<ManagedOccurrenceEvidenceResolution>
                        emptyList());
    }

    /**
     * Verifies a transition while admitting only exact candidate-member
     * lineages as supplemental gas attribution for a rejected admission.
     */
    static void verifyTransition(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output,
            List<ResultingDocument> resultingDocuments,
            List<GraphChange> graphChanges,
            List<SubscriptionDelta> subscriptionDeltas,
            List<CheckpointWrite> checkpointWrites,
            List<PublicEventOccurrence> publicEvents,
            List<GasTraceEntry> gasTrace,
            ComponentFinalizationResult reusableFinalization,
            Set<DocumentId> supplementalGasDocumentIds) {
        verifyTransition(
                input,
                output,
                resultingDocuments,
                graphChanges,
                subscriptionDeltas,
                checkpointWrites,
                publicEvents,
                gasTrace,
                reusableFinalization,
                supplementalGasDocumentIds,
                Collections.<ManagedOccurrenceEvidenceResolution>
                        emptyList());
    }

    /** Verifies a transition with exact resolution-bound retry evidence. */
    static void verifyTransition(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output,
            List<ResultingDocument> resultingDocuments,
            List<GraphChange> graphChanges,
            List<SubscriptionDelta> subscriptionDeltas,
            List<CheckpointWrite> checkpointWrites,
            List<PublicEventOccurrence> publicEvents,
            List<GasTraceEntry> gasTrace,
            ComponentFinalizationResult reusableFinalization,
            Set<DocumentId> supplementalGasDocumentIds,
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        requireDocumentContinuity(input, output, resultingDocuments);
        verifyFinalizedState(input, output, reusableFinalization);
        verifyMarkers(output);
        verifyGraphChanges(input, output, graphChanges, resolutions);
        verifyGraphGeneration(input, output);
        verifyPublicEventOrder(publicEvents);
        verifyGasDocumentContexts(
                output, gasTrace, supplementalGasDocumentIds);
        verifyCheckpointTargets(output, checkpointWrites);
        verifySubscriptionDeltas(input, output, subscriptionDeltas);
    }

    private static void verifyFinalizedState(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot asserted,
            ComponentFinalizationResult reusableFinalization) {
        ManagedDocumentGraph inputGraph = graph(input);
        ComponentFinalizationResult calculated = reusableFinalization;
        if (calculated == null) {
            calculated = new ComponentFinalizationKernel()
                    .finalizeComponents(new ComponentFinalizationInput(
                            inputGraph,
                            componentGenerations(input),
                            documentBodies(asserted),
                            asserted.occurrences()));
        } else {
            ManagedDocumentGraph assertedGraph = graph(asserted);
            Map<DocumentId, Long> expectedGenerations =
                    ComponentGenerationTransition.assign(
                            inputGraph,
                            componentGenerations(input),
                            assertedGraph);
            if (!expectedGenerations.equals(
                    calculated.componentGenerations())) {
                throw new IllegalArgumentException(
                        "Reusable finalization used a different component-generation transition");
            }
        }

        verifyOccurrenceValues(asserted);
        verifyLanguageEvidence(calculated);
        requireBindings(asserted.occurrences(),
                calculated.finalizedGraph().bindings());
        requireComponents(asserted.components(), calculated.components());
        requireDocuments(asserted.managedDocuments(), calculated);
    }

    private static ManagedDocumentGraph graph(
            AffectedClosureSnapshot snapshot) {
        ArrayList<DocumentId> documents = new ArrayList<DocumentId>();
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            documents.add(document.documentId());
        }
        return ManagedDocumentGraph.fromBindings(
                documents, snapshot.occurrences());
    }

    private static Map<DocumentId, Long> componentGenerations(
            AffectedClosureSnapshot snapshot) {
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            result.put(document.documentId(), Long.valueOf(
                    document.componentGeneration()));
        }
        return result;
    }

    private static Map<DocumentId, Node> documentBodies(
            AffectedClosureSnapshot snapshot) {
        LinkedHashMap<DocumentId, Node> result =
                new LinkedHashMap<DocumentId, Node>();
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            result.put(document.documentId(), document.document());
        }
        return result;
    }

    private static void verifyLanguageEvidence(
            ComponentFinalizationResult finalized) {
        EvidenceNodeProvider evidence = new EvidenceNodeProvider(finalized);
        VerifyingNodeProvider verifier = new VerifyingNodeProvider(evidence);
        for (FinalizedDocumentEvidence document
                : finalized.documents().values()) {
            verifier.fetchByBlueId(document.blueId());
        }
    }

    private static void verifyOccurrenceValues(
            AffectedClosureSnapshot snapshot) {
        Map<DocumentId, ManagedDocumentSnapshot> documents = documentsById(
                snapshot.managedDocuments());
        for (ManagedOccurrenceBinding occurrence : snapshot.occurrences()) {
            Node value = NodePathEditor.getOrNull(
                    documents.get(occurrence.sourceDocumentId()).document(),
                    occurrence.sourcePath());
            if (value == null) {
                if (occurrence.active()) {
                    throw new IllegalArgumentException(
                            "Active occurrence path is absent from its source document");
                }
                continue;
            }
            if (value.isReferenceOnly()) {
                if (!occurrence.expectedTargetBlueId().equals(
                        value.getBlueId())) {
                    throw new IllegalArgumentException(
                            "Occurrence value does not establish its expected target BlueId");
                }
                continue;
            }
            ManagedDocumentSnapshot target = documents.get(
                    occurrence.targetDocumentId());
            if (!occurrence.active()
                    && target != null
                    && !target.blueId().equals(
                            occurrence.expectedTargetBlueId())
                    && occurrence.expectedTargetBlueId().indexOf('#') >= 0) {
                // A historical cyclic materialization requires its historical
                // provider proof, which is invocation evidence rather than
                // part of the current authoritative component snapshot.
                continue;
            }
            CyclicSetProof proof = proofFor(
                    snapshot, occurrence.targetDocumentId());
            VerifyingNodeProvider verifier = new VerifyingNodeProvider(
                    new EvidenceNodeProvider(
                            occurrence.expectedTargetBlueId(), value, proof));
            verifier.fetchByBlueId(occurrence.expectedTargetBlueId());
        }
    }

    private static CyclicSetProof proofFor(
            AffectedClosureSnapshot snapshot,
            DocumentId documentId) {
        for (ComponentSnapshot component : snapshot.components()) {
            if (component.orderedMemberDocumentIds().contains(documentId)) {
                return component.completeCyclicProof();
            }
        }
        throw new IllegalArgumentException(
                "Occurrence target is absent from the component partition");
    }

    private static void requireBindings(
            List<ManagedOccurrenceBinding> asserted,
            List<ManagedOccurrenceBinding> calculated) {
        if (asserted.size() != calculated.size()) {
            throw new IllegalArgumentException(
                    "Finalized occurrence evidence is incomplete");
        }
        for (int index = 0; index < asserted.size(); index++) {
            ManagedOccurrenceBinding left = asserted.get(index);
            ManagedOccurrenceBinding right = calculated.get(index);
            if (!sameBinding(left, right)) {
                throw new IllegalArgumentException(
                        "Finalized occurrence binding disagrees with exact document state");
            }
        }
    }

    private static void requireComponents(
            List<ComponentSnapshot> asserted,
            List<FinalizedComponentEvidence> calculated) {
        if (asserted.size() != calculated.size()) {
            throw new IllegalArgumentException(
                    "Resulting components disagree with the exact graph partition");
        }
        for (int index = 0; index < asserted.size(); index++) {
            ComponentSnapshot left = asserted.get(index);
            ComponentSnapshot right = calculated.get(index).component();
            if (!sameComponent(left, right)) {
                throw new IllegalArgumentException(
                        "Resulting component identity, generation, or proof is not authoritative");
            }
        }
    }

    private static void requireDocuments(
            List<ManagedDocumentSnapshot> asserted,
            ComponentFinalizationResult calculated) {
        if (asserted.size() != calculated.documents().size()) {
            throw new IllegalArgumentException(
                    "Finalized documents do not cover the asserted snapshot");
        }
        for (ManagedDocumentSnapshot document : asserted) {
            FinalizedDocumentEvidence exact = calculated.document(
                    document.documentId());
            if (!document.blueId().equals(exact.blueId())
                    || document.componentGeneration()
                    != exact.componentGeneration()) {
                throw new IllegalArgumentException(
                        "Managed document BlueId or body is not the exact finalized state");
            }
        }
    }

    private static boolean sameBinding(
            ManagedOccurrenceBinding left,
            ManagedOccurrenceBinding right) {
        return left.occurrenceIdentity().equals(right.occurrenceIdentity())
                && left.bindingIdentity().equals(right.bindingIdentity())
                && left.bindingPolicyIdentity().equals(
                        right.bindingPolicyIdentity())
                && left.sourceDocumentId().equals(right.sourceDocumentId())
                && left.sourceAddress().equals(right.sourceAddress())
                && left.targetDocumentId().equals(right.targetDocumentId())
                && left.expectedTargetBlueId().equals(
                        right.expectedTargetBlueId())
                && left.active() == right.active()
                && Objects.equals(left.pendingHistoricalEpoch(),
                        right.pendingHistoricalEpoch());
    }

    private static boolean sameComponent(
            ComponentSnapshot left,
            ComponentSnapshot right) {
        if (!left.componentIdentity().equals(right.componentIdentity())
                || !left.componentStateIdentity().equals(
                        right.componentStateIdentity())
                || left.componentGeneration()
                != right.componentGeneration()
                || left.kind() != right.kind()
                || !left.orderedMemberDocumentIds().equals(
                        right.orderedMemberDocumentIds())
                || !left.orderedMemberBlueIds().equals(
                        right.orderedMemberBlueIds())
                || !Objects.equals(left.masterBlueId(), right.masterBlueId())
                || !Objects.equals(left.cyclicProofIdentity(),
                        right.cyclicProofIdentity())) {
            return false;
        }
        if (left.completeCyclicProof() == null) {
            return right.completeCyclicProof() == null;
        }
        List<Node> leftProof = left.completeCyclicProof()
                .declaredPlaceholderSet();
        List<Node> rightProof = right.completeCyclicProof()
                .declaredPlaceholderSet();
        if (leftProof.size() != rightProof.size()) {
            return false;
        }
        for (int index = 0; index < leftProof.size(); index++) {
            if (!NodeWireForm.get(leftProof.get(index)).equals(
                    NodeWireForm.get(rightProof.get(index)))) {
                return false;
            }
        }
        return true;
    }

    private static void verifyMarkers(AffectedClosureSnapshot snapshot) {
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            verifyMarkers(document.document(), document.initialized(),
                    document.terminated());
        }
    }

    private static void verifyMarkers(
            Node document,
            boolean initialized,
            boolean terminated) {
        Node contracts = document.getContracts();
        Map<String, Node> values = contracts == null
                ? null : contracts.getProperties();
        Node initializedMarker = values == null ? null
                : values.get(ProcessorContractConstants.KEY_INITIALIZED);
        Node terminatedMarker = values == null ? null
                : values.get(ProcessorContractConstants.KEY_TERMINATED);
        if (initializedMarker != null) {
            requireRuntimeType(initializedMarker,
                    RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER,
                    ProcessorContractConstants.KEY_INITIALIZED);
            Map<String, Node> markerValues =
                    initializedMarker.getProperties();
            Node exactDocument = markerValues == null ? null
                    : markerValues.get(ProcessorContractConstants.KEY_DOCUMENT);
            if (exactDocument == null
                    || markerValues.containsKey(
                            ProcessorContractConstants.LEGACY_KEY_DOCUMENT_ID)) {
                throw new IllegalArgumentException(
                        "Invalid direct initialized marker");
            }
            BlueIdReferenceValidator.validate(exactDocument);
        }
        if (terminatedMarker != null) {
            requireRuntimeType(terminatedMarker,
                    RuntimeBlueIds.PROCESSING_TERMINATED_MARKER,
                    ProcessorContractConstants.KEY_TERMINATED);
            String cause = stringProperty(terminatedMarker,
                    ProcessorContractConstants.KEY_CAUSE);
            if (cause == null || cause.isEmpty()) {
                throw new IllegalArgumentException(
                        "Invalid direct terminated marker");
            }
        }
        if (initialized != (initializedMarker != null)
                || terminated != (terminatedMarker != null)) {
            throw new IllegalArgumentException(
                    "Document marker assertions disagree with exact content");
        }
    }

    private static void requireRuntimeType(
            Node marker,
            String expected,
            String label) {
        Node type = marker.getType();
        String actual = type != null && type.isReferenceOnly()
                ? type.getBlueId()
                : null;
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Invalid direct " + label + " marker");
        }
    }

    private static String stringProperty(Node node, String key) {
        Node value = node.getProperties() == null ? null
                : node.getProperties().get(key);
        Object raw = value == null ? null : value.getValue();
        return raw instanceof String ? (String) raw : null;
    }

    private static void requireDocumentContinuity(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output,
            List<ResultingDocument> resultingDocuments) {
        Map<DocumentId, ManagedDocumentSnapshot> before =
                documentsById(input.managedDocuments());
        if (!before.keySet().equals(
                documentsById(output.managedDocuments()).keySet())
                || before.size() != resultingDocuments.size()) {
            throw new IllegalArgumentException(
                    "Result must preserve the complete affected document set");
        }
        for (ResultingDocument result : resultingDocuments) {
            ManagedDocumentSnapshot inputDocument = before.get(
                    result.documentId());
            ManagedDocumentSnapshot outputDocument = output.managedDocument(
                    result.documentId());
            if (inputDocument == null
                    || !inputDocument.blueId().equals(
                            result.beforeBlueId())
                    || outputDocument == null
                    || outputDocument.epoch() != result.epoch()) {
                throw new IllegalArgumentException(
                        "Resulting document continuity disagrees with closure state");
            }
            long beforeEpoch = inputDocument.epoch();
            long afterEpoch = outputDocument.epoch();
            if (afterEpoch < beforeEpoch
                    || afterEpoch - beforeEpoch > 1L) {
                throw new IllegalArgumentException(
                        "Managed document epoch must be preserved or advance once");
            }
            if (inputDocument.blueId().equals(outputDocument.blueId())
                    && afterEpoch != beforeEpoch) {
                throw new IllegalArgumentException(
                        "An unchanged managed head cannot advance its epoch");
            }
        }
    }

    private static void verifyGraphGeneration(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output) {
        Set<String> before = activeOccurrenceIds(input.occurrences());
        Set<String> after = activeOccurrenceIds(output.occurrences());
        long expected = input.graphGeneration();
        if (!before.equals(after)) {
            if (expected == ClosureValueSupport.MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException(
                        "graphGeneration cannot overflow the safe-integer range");
            }
            expected++;
        }
        if (output.graphGeneration() != expected) {
            throw new IllegalArgumentException(
                    "graphGeneration violates the active-occurrence transition law");
        }
    }

    private static void verifyGraphChanges(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output,
            List<GraphChange> changes,
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        Map<String, ManagedOccurrenceBinding> beforeRows =
                occurrencesById(input.occurrences());
        Map<String, ManagedOccurrenceBinding> afterRows =
                occurrencesById(output.occurrences());
        Map<String, GraphChange.Side> active = activeSides(input.occurrences());

        for (GraphChange change : changes) {
            if (change.changeKind() == GraphChange.Kind.ADD) {
                GraphChange.Side selected = change.after();
                ManagedOccurrenceBinding lineage = afterRows.get(
                        selected.occurrenceIdentity());
                requireGraphChangeLocation(change, lineage);
                verifyGraphSide(selected, lineage);
                GraphChange.Side current = active.get(
                        selected.occurrenceIdentity());
                ManagedOccurrenceBinding reserved = beforeRows.get(
                        selected.occurrenceIdentity());
                if (current != null || reserved == null || reserved.active()
                        || !sameLineage(reserved, lineage)) {
                    throw new IllegalArgumentException(
                            "Graph ADD must activate an input prospective row");
                }
                active.put(selected.occurrenceIdentity(), change.after());
            } else if (change.changeKind() == GraphChange.Kind.REMOVE) {
                GraphChange.Side selected = change.before();
                ManagedOccurrenceBinding lineage = beforeRows.get(
                        selected.occurrenceIdentity());
                requireGraphChangeLocation(change, lineage);
                verifyGraphSide(selected, lineage);
                GraphChange.Side current = active.get(
                        selected.occurrenceIdentity());
                if (!sameSide(current, change.before())) {
                    throw new IllegalArgumentException(
                            "Graph REMOVE before side is not authoritative");
                }
                requireRetirementSuccessor(
                        lineage, input, output, resolutions);
                active.remove(selected.occurrenceIdentity());
            } else {
                verifyRebind(change, beforeRows, afterRows, active);
            }
        }
        Map<String, GraphChange.Side> expected = activeSides(
                output.occurrences());
        if (active.size() != expected.size()) {
            throw new IllegalArgumentException(
                    "Graph changes do not produce the resulting active graph");
        }
        for (Map.Entry<String, GraphChange.Side> entry : expected.entrySet()) {
            if (!sameSide(active.get(entry.getKey()), entry.getValue())) {
                throw new IllegalArgumentException(
                        "Graph changes do not produce the resulting bindings");
            }
        }
    }

    private static void verifyRebind(
            GraphChange change,
            Map<String, ManagedOccurrenceBinding> beforeRows,
            Map<String, ManagedOccurrenceBinding> afterRows,
            Map<String, GraphChange.Side> active) {
        GraphChange.Side before = change.before();
        GraphChange.Side after = change.after();
        ManagedOccurrenceBinding beforeLineage = beforeRows.get(
                before.occurrenceIdentity());
        ManagedOccurrenceBinding afterLineage = afterRows.get(
                after.occurrenceIdentity());
        requireGraphChangeLocation(change, beforeLineage);
        requireGraphChangeLocation(change, afterLineage);
        verifyGraphSide(before, beforeLineage);
        verifyGraphSide(after, afterLineage);
        if (!sameSide(active.get(before.occurrenceIdentity()), before)) {
            throw new IllegalArgumentException(
                    "Graph REBIND before side is not authoritative");
        }

        if (before.occurrenceIdentity().equals(
                after.occurrenceIdentity())) {
            if (!beforeLineage.active() || !afterLineage.active()
                    || !sameLineage(beforeLineage, afterLineage)) {
                throw new IllegalArgumentException(
                        "Same-lineage REBIND must preserve its active occurrence lineage");
            }
            active.put(after.occurrenceIdentity(), after);
            return;
        }

        if (!beforeLineage.active() || !afterLineage.active()
                || !beforeLineage.sourceDocumentId().equals(
                        afterLineage.sourceDocumentId())
                || !beforeLineage.sourcePath().equals(
                        afterLineage.sourcePath())
                || !beforeLineage.bindingPolicyIdentity().equals(
                        afterLineage.bindingPolicyIdentity())
                || beforeLineage.targetDocumentId().equals(
                        afterLineage.targetDocumentId())
                || beforeLineage.activationGeneration()
                        == ClosureValueSupport.MAX_SAFE_INTEGER
                || afterLineage.activationGeneration()
                        != beforeLineage.activationGeneration() + 1L
                || beforeRows.containsKey(after.occurrenceIdentity())
                || afterRows.containsKey(before.occurrenceIdentity())
                || active.containsKey(after.occurrenceIdentity())) {
            throw new IllegalArgumentException(
                    "Different-lineage REBIND must atomically retire the old occurrence and activate a fresh next-generation retarget");
        }
        active.remove(before.occurrenceIdentity());
        active.put(after.occurrenceIdentity(), after);
    }

    private static void requireGraphChangeLocation(
            GraphChange change,
            ManagedOccurrenceBinding lineage) {
        if (lineage == null
                || !lineage.sourceDocumentId().equals(
                        change.sourceDocumentId())
                || !lineage.sourcePath().equals(change.sourcePath())) {
            throw new IllegalArgumentException(
                    "Graph change does not name a known occurrence lineage");
        }
    }

    private static void requireRetirementSuccessor(
            ManagedOccurrenceBinding removed,
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output,
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        ManagedOccurrenceBinding successor = occurrenceAt(
                output.occurrences(), removed.sourceDocumentId(),
                removed.sourcePath());
        if (successor != null
                && historicalRetargetSuccessor(
                        removed, successor, input, resolutions)) {
            return;
        }
        if (successor == null || successor.active()
                || successor.activationGeneration()
                != nextGeneration(removed.activationGeneration())
                || !sameStableTargetAndPolicy(removed, successor)
                || successor.pendingHistoricalEpoch() != null) {
            throw new IllegalArgumentException(
                    "Graph REMOVE lacks its exact inactive retirement successor");
        }
        ManagedDocumentSnapshot target = output.managedDocument(
                successor.targetDocumentId());
        if (target == null
                || !target.blueId().equals(
                        successor.expectedTargetBlueId())) {
            throw new IllegalArgumentException(
                    "Retirement successor does not bind the current target state");
        }
    }

    private static boolean historicalRetargetSuccessor(
            ManagedOccurrenceBinding removed,
            ManagedOccurrenceBinding successor,
            AffectedClosureSnapshot input,
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        if (successor.active()
                || successor.pendingHistoricalEpoch() == null
                || successor.activationGeneration()
                        != nextGeneration(removed.activationGeneration())
                || !removed.sourceDocumentId().equals(
                        successor.sourceDocumentId())
                || !removed.sourcePath().equals(successor.sourcePath())
                || !removed.bindingPolicyIdentity().equals(
                        successor.bindingPolicyIdentity())
                || removed.targetDocumentId().equals(
                        successor.targetDocumentId())) {
            return false;
        }
        for (ManagedOccurrenceEvidenceResolution resolution
                : Objects.requireNonNull(resolutions, "resolutions")) {
            ManagedOccurrenceEvidenceDemand demand = resolution.demand();
            if (demand.inputClosureIdentity().equals(
                            input.closureIdentity())
                    && demand.inputGraphGeneration()
                            == input.graphGeneration()
                    && demand.sourceDocumentId().equals(
                            removed.sourceDocumentId())
                    && demand.sourcePath().equals(removed.sourcePath())
                    && resolution.targetDocumentId().equals(
                            successor.targetDocumentId())
                    && resolution.pendingHistoricalEpoch()
                            == successor.pendingHistoricalEpoch().longValue()
                    && demand.suppliedValueBlueId().equals(
                            successor.expectedTargetBlueId())) {
                return true;
            }
        }
        return false;
    }

    private static long nextGeneration(long generation) {
        if (generation == ClosureValueSupport.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    "Occurrence activation generation cannot overflow");
        }
        return generation + 1L;
    }

    private static ManagedOccurrenceBinding occurrenceAt(
            List<ManagedOccurrenceBinding> rows,
            DocumentId source,
            String path) {
        for (ManagedOccurrenceBinding row : rows) {
            if (row.sourceDocumentId().equals(source)
                    && row.sourcePath().equals(path)) {
                return row;
            }
        }
        return null;
    }

    private static boolean sameLineage(
            ManagedOccurrenceBinding left,
            ManagedOccurrenceBinding right) {
        return left.occurrenceIdentity().equals(right.occurrenceIdentity())
                && left.activationGeneration()
                == right.activationGeneration()
                && sameStableTargetAndPolicy(left, right);
    }

    private static boolean sameStableTargetAndPolicy(
            ManagedOccurrenceBinding left,
            ManagedOccurrenceBinding right) {
        return left.sourceDocumentId().equals(right.sourceDocumentId())
                && left.sourcePath().equals(right.sourcePath())
                && left.targetDocumentId().equals(right.targetDocumentId())
                && left.bindingPolicyIdentity().equals(
                        right.bindingPolicyIdentity());
    }

    private static void verifyGraphSide(
            GraphChange.Side side,
            ManagedOccurrenceBinding lineage) {
        if (side == null) {
            return;
        }
        String binding = IDENTITIES.managedOccurrenceBindingIdentity(
                lineage.sourceDocumentId(),
                lineage.sourceAddress(),
                side.targetDocumentId(),
                side.targetBlueId(),
                lineage.bindingPolicyIdentity());
        if (side.activationGeneration()
                != lineage.activationGeneration()
                || !side.occurrenceIdentity().equals(
                        lineage.occurrenceIdentity())
                || !side.bindingIdentity().equals(binding)
                || !side.targetDocumentId().equals(
                        lineage.targetDocumentId())) {
            throw new IllegalArgumentException(
                    "Graph-change side does not identify its occurrence state");
        }
    }

    private static Map<String, GraphChange.Side> activeSides(
            List<ManagedOccurrenceBinding> occurrences) {
        LinkedHashMap<String, GraphChange.Side> result =
                new LinkedHashMap<String, GraphChange.Side>();
        for (ManagedOccurrenceBinding occurrence : occurrences) {
            if (occurrence.active()) {
                result.put(occurrence.occurrenceIdentity(), side(occurrence));
            }
        }
        return result;
    }

    private static GraphChange.Side side(ManagedOccurrenceBinding occurrence) {
        return new GraphChange.Side(
                occurrence.activationGeneration(),
                occurrence.occurrenceIdentity(),
                occurrence.bindingIdentity(),
                occurrence.targetDocumentId(),
                occurrence.expectedTargetBlueId());
    }

    private static boolean sameSide(
            GraphChange.Side left,
            GraphChange.Side right) {
        return left != null && right != null
                && left.activationGeneration() == right.activationGeneration()
                && left.occurrenceIdentity().equals(right.occurrenceIdentity())
                && left.bindingIdentity().equals(right.bindingIdentity())
                && left.targetDocumentId().equals(right.targetDocumentId())
                && left.targetBlueId().equals(right.targetBlueId());
    }

    private static void verifyPublicEventOrder(
            List<PublicEventOccurrence> events) {
        long previous = -1L;
        for (PublicEventOccurrence event : events) {
            if (event.eventOccurrenceOrdinal() <= previous) {
                throw new IllegalArgumentException(
                        "Public event occurrence ordinals must be strictly increasing");
            }
            previous = event.eventOccurrenceOrdinal();
        }
    }

    private static void verifyCheckpointTargets(
            AffectedClosureSnapshot output,
            List<CheckpointWrite> writes) {
        for (CheckpointWrite write : writes) {
            if (!output.contains(
                    write.targetManagedScopeKey().documentId())) {
                throw new IllegalArgumentException(
                        "Checkpoint target is outside the resulting closure");
            }
        }
    }

    private static void verifySubscriptionDeltas(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output,
            List<SubscriptionDelta> deltas) {
        Map<DocumentId, ManagedDocumentSnapshot> before =
                documentsById(input.managedDocuments());
        Map<DocumentId, ManagedDocumentSnapshot> after =
                documentsById(output.managedDocuments());
        Set<String> occurrences = new HashSet<String>();
        for (SubscriptionDelta delta : deltas) {
            if (!occurrences.add(delta.channelOccurrenceIdentity())) {
                throw new IllegalArgumentException(
                        "A Channel occurrence has more than one subscription delta");
            }
            verifySubscriptionSide(delta.beforeSubscription(), before,
                    input.graphGeneration(), "before");
            verifySubscriptionSide(delta.afterSubscription(), after,
                    output.graphGeneration(), "after");
        }
    }

    private static void verifySubscriptionSide(
            SubscriptionState state,
            Map<DocumentId, ManagedDocumentSnapshot> documents,
            long graphGeneration,
            String label) {
        if (state == null) {
            return;
        }
        DocumentId documentId = state.channelOccurrence()
                .managedDocumentId();
        ManagedDocumentSnapshot document = documents.get(documentId);
        if (document == null
                || !document.blueId().equals(state.documentBlueId())
                || state.graphGeneration() != graphGeneration
                || state.componentGeneration()
                != document.componentGeneration()) {
            throw new IllegalArgumentException(
                    "Subscription " + label
                            + " side disagrees with closure state");
        }
    }

    private static void verifyGasDocumentContexts(
            AffectedClosureSnapshot output,
            List<GasTraceEntry> gasTrace,
            Set<DocumentId> supplementalDocumentIds) {
        Set<DocumentId> supplemental = Objects.requireNonNull(
                supplementalDocumentIds, "supplementalGasDocumentIds");
        for (GasTraceEntry entry : gasTrace) {
            if (entry.documentId() != null
                    && !output.contains(entry.documentId())
                    && !supplemental.contains(entry.documentId())) {
                throw new IllegalArgumentException(
                        "Gas trace names a document outside the closure");
            }
        }
    }

    private static Set<String> activeOccurrenceIds(
            List<ManagedOccurrenceBinding> occurrences) {
        Set<String> result = new HashSet<String>();
        for (ManagedOccurrenceBinding occurrence : occurrences) {
            if (occurrence.active()) {
                result.add(occurrence.occurrenceIdentity());
            }
        }
        return result;
    }

    private static Map<String, ManagedOccurrenceBinding> occurrencesById(
            List<ManagedOccurrenceBinding> occurrences) {
        Map<String, ManagedOccurrenceBinding> result =
                new HashMap<String, ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding occurrence : occurrences) {
            result.put(occurrence.occurrenceIdentity(), occurrence);
        }
        return result;
    }

    private static Map<DocumentId, ManagedDocumentSnapshot> documentsById(
            List<ManagedDocumentSnapshot> documents) {
        Map<DocumentId, ManagedDocumentSnapshot> result =
                new LinkedHashMap<DocumentId, ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot document : documents) {
            result.put(document.documentId(), document);
        }
        return result;
    }

    /** In-memory proof-bearing provider used only to invoke Language verifier. */
    private static final class EvidenceNodeProvider
            implements NodeProvider, CyclicAwareNodeProvider {

        private final Map<String, Node> documents =
                new HashMap<String, Node>();
        private final Map<String, CyclicSetProof> proofs =
                new HashMap<String, CyclicSetProof>();

        private EvidenceNodeProvider(ComponentFinalizationResult finalized) {
            for (FinalizedDocumentEvidence document
                    : finalized.documents().values()) {
                documents.put(document.blueId(), document.document());
            }
            for (FinalizedComponentEvidence evidence
                    : finalized.components()) {
                ComponentSnapshot component = evidence.component();
                if (component.kind() != ComponentKind.CYCLIC) {
                    continue;
                }
                CyclicSetProof proof = component.completeCyclicProof();
                for (String blueId : component.orderedMemberBlueIds()) {
                    proofs.put(BlueIds.cyclicSetMasterBlueId(blueId), proof);
                }
            }
        }

        private EvidenceNodeProvider(
                String blueId,
                Node document,
                CyclicSetProof proof) {
            documents.put(blueId, document.clone());
            if (proof != null) {
                proofs.put(BlueIds.cyclicSetMasterBlueId(blueId), proof);
            }
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node document = documents.get(blueId);
            return document == null ? null
                    : Collections.singletonList(document.clone());
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            CyclicSetProof proof = proofs.get(
                    BlueIds.cyclicSetMasterBlueId(blueId));
            return proof == null ? CyclicSetProofResult.notFound()
                    : CyclicSetProofResult.found(proof);
        }
    }
}
