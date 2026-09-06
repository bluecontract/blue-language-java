package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.snapshot.FrozenNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Proposed complete evidence for one committed historical representation position.
 * The host MUST independently authenticate the original input/result and position
 * against its durable publication store; constructing this value is not that authority.
 */
public final class ManagedRepresentationTransition {
    private final DocumentId documentId;
    private final long epoch;
    private final String anchorReceiptIdentity;
    private final String predecessorPositionIdentity;
    private final ClosureInvocationInput originalInput;
    private final ClosureProcessResult originalResult;
    private final ManagedDocumentTransitionReceipt transition;
    private final ResultingDocument after;
    private final String positionIdentity;

    /**
     * Verifies complete evidence for one permitted same-epoch representation change.
     * The host must additionally authenticate this publication against its durable store.
     * @param documentId authoritative source lineage
     * @param epoch unchanged source epoch
     * @param anchorReceiptIdentity numbered receipt anchoring this historical chain
     * @param predecessorPositionIdentity exact preceding position in that chain
     * @param originalInput complete original processor invocation
     * @param originalResult complete committed result and companion
     * @param transitionReceiptIdentity exact selected source transition receipt
     */
    public ManagedRepresentationTransition(DocumentId documentId, long epoch,
            String anchorReceiptIdentity, String predecessorPositionIdentity,
            ClosureInvocationInput originalInput, ClosureProcessResult originalResult,
            String transitionReceiptIdentity) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.epoch = ClosureValueSupport.requireSafeInteger(epoch, "epoch");
        this.anchorReceiptIdentity = ClosureValueSupport.requireSha256Identity(
                anchorReceiptIdentity, "anchorReceiptIdentity");
        this.predecessorPositionIdentity = ClosureValueSupport.requireSha256Identity(
                predecessorPositionIdentity, "predecessorPositionIdentity");
        this.originalInput = Objects.requireNonNull(originalInput, "originalInput");
        this.originalResult = Objects.requireNonNull(originalResult, "originalResult");
        require(originalResult.commits() && originalResult.platformCommitCompanion() != null
                && originalResult.platformCommitCompanion().bindsManagedTransitionReceipts(),
                "Representation transition has no committing companion");
        require(originalResult.invocationIdentity().equals(originalInput.invocationIdentity())
                && originalResult.inputClosureIdentity().equals(originalInput.snapshot().closureIdentity()),
                "Representation transition belongs to another original invocation");
        ClosureEvidenceVerifier.verifySnapshot(originalInput.snapshot());
        require(ClosureIdentityService.INSTANCE.invocationIdentity(originalInput)
                .equals(originalInput.invocationIdentity()), "Original invocation identity mismatch");
        ManagedDocumentTransitionReceipt selected = null;
        for (ManagedDocumentTransitionReceipt candidate : originalResult.managedTransitionReceipts()) {
            if (candidate.documentId().equals(documentId)) {
                require(selected == null, "Duplicate representation source transition");
                selected = candidate;
            }
        }
        require(selected != null && selected.transitionReceiptIdentity().equals(transitionReceiptIdentity),
                "Original result does not contain the complete selected transition");
        transition = selected;
        ResultingDocument successor = null;
        for (ResultingDocument candidate : originalResult.resultingDocuments()) {
            if (candidate.documentId().equals(documentId)) {
                require(successor == null, "Duplicate representation result document");
                successor = candidate;
            }
        }
        require(successor != null, "Representation result document is absent");
        after = successor;
        ManagedDocumentSnapshot before = originalInput.snapshot().managedDocument(documentId);
        require(before != null && before.epoch() == epoch && after.epoch() == epoch,
                "Representation evidence changes or misstates the source epoch");
        require(before.initialized() && after.initialized() && !before.terminated() && !after.terminated()
                && before.publicRoot() == after.publicRoot(), "Representation changes source lifecycle");
        require(transition.beforeBlueId().equals(before.blueId())
                && transition.afterBlueId().equals(after.afterBlueId())
                && transition.beforeBlueId().equals(after.beforeBlueId())
                && !transition.beforeBlueId().equals(transition.afterBlueId())
                && transition.emittedRootEvents().isEmpty(),
                "Representation evidence changes source events or exact endpoints");
        require(reconcilesPendingTarget(originalInput, originalResult, documentId),
                "Representation source is not the selected pending historical target");
        require(referenceOnly(before.document(), after.document(),
                owned(originalInput.snapshot().occurrences()), owned(originalResult.occurrenceBindings())),
                "Representation body change lacks complete same-lineage binding proof");
        positionIdentity = ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.MANAGED_REPRESENTATION_POSITION, identityValue());
    }

    private List<ManagedOccurrenceBinding> owned(List<ManagedOccurrenceBinding> rows) {
        List<ManagedOccurrenceBinding> owned = new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding row : rows) if (row.sourceDocumentId().equals(documentId)) owned.add(row);
        return owned;
    }

    private static boolean reconcilesPendingTarget(ClosureInvocationInput input,
            ClosureProcessResult result, DocumentId documentId) {
        for (ManagedOccurrenceBinding row : input.snapshot().occurrences()) {
            if (row.active() || row.pendingHistoricalEpoch() == null
                    || !row.targetDocumentId().equals(documentId)) continue;
            if (input.cause() instanceof ManagedHistoryStep
                    && !((ManagedHistoryStep) input.cause()).targetOccurrenceIdentity()
                    .equals(row.occurrenceIdentity())) continue;
            ManagedDocumentSnapshot source = input.snapshot().managedDocument(row.sourceDocumentId());
            for (ResultingDocument next : result.resultingDocuments()) {
                if (!next.documentId().equals(source.documentId())) continue;
                Node oldValue = NodePathEditor.getOrNull(source.document(), row.sourcePath());
                Node newValue = NodePathEditor.getOrNull(next.document(), row.sourcePath());
                if (!Objects.equals(oldValue, newValue)) return true;
                for (ManagedOccurrenceBinding current : result.occurrenceBindings()) {
                    if (current.occurrenceIdentity().equals(row.occurrenceIdentity()) && current.active()) return true;
                }
            }
        }
        return false;
    }

    private boolean referenceOnly(Node before, Node after,
            List<ManagedOccurrenceBinding> prior, List<ManagedOccurrenceBinding> next) {
        if (prior.isEmpty() || prior.size() != next.size()) return false;
        Map<String, ManagedOccurrenceBinding> unmatched = new HashMap<String, ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding row : next)
            if (unmatched.put(row.occurrenceIdentity(), row) != null) return false;
        Node normalizedBefore = before.clone(), normalizedAfter = after.clone();
        for (ManagedOccurrenceBinding row : prior) {
            ManagedOccurrenceBinding successor = unmatched.remove(row.occurrenceIdentity());
            if (successor == null || !successor.sourceDocumentId().equals(row.sourceDocumentId())
                    || !successor.bindingPolicyIdentity().equals(row.bindingPolicyIdentity())
                    || !successor.sourcePath().equals(row.sourcePath())
                    || !successor.targetDocumentId().equals(row.targetDocumentId())
                    || successor.activationGeneration() != row.activationGeneration()
                    || successor.active() != row.active()
                    || !Objects.equals(successor.pendingHistoricalEpoch(), row.pendingHistoricalEpoch())
                    || !Objects.equals(successor.pendingRepresentationCursor(), row.pendingRepresentationCursor())) return false;
            if (!row.active() && row.pendingHistoricalEpoch() == null) {
                // Retired binding metadata follows the finalized target, although
                // its former source path no longer participates in the graph.
                // Prove both exact target values; normalize no source-path bytes.
                ManagedDocumentSnapshot oldTarget = originalInput.snapshot().managedDocument(row.targetDocumentId());
                ResultingDocument newTarget = null;
                for (ResultingDocument candidate : originalResult.resultingDocuments()) {
                    if (!candidate.documentId().equals(row.targetDocumentId())) continue;
                    if (newTarget != null) return false;
                    newTarget = candidate;
                }
                if (oldTarget == null || newTarget == null
                        || !exact(oldTarget.document()).equals(row.expectedTargetBlueId())
                        || !exact(newTarget.document()).equals(successor.expectedTargetBlueId())) return false;
                continue;
            }
            Node previous = NodePathEditor.getOrNull(before, row.sourcePath());
            Node current = NodePathEditor.getOrNull(after, row.sourcePath());
            if (previous == null || current == null || !exact(previous).equals(row.expectedTargetBlueId())
                    || !exact(current).equals(successor.expectedTargetBlueId())) return false;
            Node reference = new Node().blueId(row.expectedTargetBlueId());
            NodePathEditor.put(normalizedBefore, row.sourcePath(), reference.clone());
            NodePathEditor.put(normalizedAfter, row.sourcePath(), reference.clone());
        }
        return exact(normalizedBefore).equals(exact(normalizedAfter));
    }

    private static String exact(Node node) {
        return FrozenNode.fromNode(NodeToBlueIdInput.stripResolvedBlueIdMetadata(node)).blueId();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    Map<String, Object> identityValue() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("documentId", documentId.value()); value.put("epoch", Long.valueOf(epoch));
        value.put("anchorReceiptIdentity", anchorReceiptIdentity);
        value.put("predecessorPositionIdentity", predecessorPositionIdentity);
        value.put("beforeBlueId", transition.beforeBlueId()); value.put("afterBlueId", transition.afterBlueId());
        value.put("transitionReceiptIdentity", transition.transitionReceiptIdentity());
        value.put("originalInvocationIdentity", originalInput.invocationIdentity());
        value.put("inputClosureIdentity", originalResult.inputClosureIdentity());
        value.put("outputClosureIdentity", originalResult.outputClosureIdentity());
        value.put("commitCompanionIdentity", originalResult.platformCommitCompanion().companionIdentity());
        return value;
    }
    /** @return the authoritative source lineage */
    public DocumentId documentId() { return documentId; }
    /** @return the unchanged source epoch */
    public long epoch() { return epoch; }
    /** @return the numbered receipt anchoring this chain */
    public String anchorReceiptIdentity() { return anchorReceiptIdentity; }
    /** @return the exact preceding historical position */
    public String predecessorPositionIdentity() { return predecessorPositionIdentity; }
    /** @return the identity binding this transition and its historical position */
    public String positionIdentity() { return positionIdentity; }
    /** @return the complete original processor invocation */
    public ClosureInvocationInput originalInput() { return originalInput; }
    /** @return the complete original committed result */
    public ClosureProcessResult originalResult() { return originalResult; }
    /** @return the selected complete source transition receipt */
    public ManagedDocumentTransitionReceipt transitionReceipt() { return transition; }
    /** @return a detached copy of the exact historical successor document */
    public Node afterDocument() { return after.document(); }
}
