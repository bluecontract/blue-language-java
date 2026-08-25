package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.FrozenJsonPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tentative local effects of one isolated document step.
 *
 * <p>This value intentionally has no after-BlueId accessor or constructor
 * argument. Only the closure orchestrator may calculate an ordinary identity
 * or complete-set cyclic {@code MASTER#index} identities after applying this
 * local body.</p>
 */
public final class LocalDocumentStepResult {

    private final DocumentId documentId;
    private final String workOccurrenceIdentity;
    private final String beforeBlueId;
    private final Node resultingBody;
    private final List<Node> emittedEvents;
    private final List<FrozenJsonPatch> orderedPatches;
    private final long gasBefore;
    private final long gasAfter;
    private final boolean identityAffecting;
    private final DocumentTransitionEvidence transitionEvidence;

    /**
     * Creates one pre-finalization local result.
     *
     * @param documentId target managed document
     * @param workOccurrenceIdentity exact owning work identity
     * @param beforeBlueId exact target identity before execution
     * @param resultingBody resulting local body before identity finalization
     * @param emittedEvents ordered locally emitted events
     * @param orderedPatches exact applied patches in encounter order
     * @param gasBefore shared gas total before execution
     * @param gasAfter shared gas total after execution
     * @param identityAffecting whether identity reconciliation is required
     */
    public LocalDocumentStepResult(
            DocumentId documentId,
            String workOccurrenceIdentity,
            String beforeBlueId,
            Node resultingBody,
            List<Node> emittedEvents,
            List<FrozenJsonPatch> orderedPatches,
            long gasBefore,
            long gasAfter,
            boolean identityAffecting) {
        this(documentId,
                workOccurrenceIdentity,
                beforeBlueId,
                resultingBody,
                emittedEvents,
                orderedPatches,
                gasBefore,
                gasAfter,
                identityAffecting,
                null);
    }

    LocalDocumentStepResult(
            DocumentId documentId,
            String workOccurrenceIdentity,
            String beforeBlueId,
            Node resultingBody,
            List<Node> emittedEvents,
            List<FrozenJsonPatch> orderedPatches,
            long gasBefore,
            long gasAfter,
            boolean identityAffecting,
            DocumentTransitionEvidence transitionEvidence) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.workOccurrenceIdentity =
                ClosureValueSupport.requireSha256Identity(
                        workOccurrenceIdentity,
                        "workOccurrenceIdentity");
        this.beforeBlueId = ClosureValueSupport.requireBlueId(
                beforeBlueId, "beforeBlueId");
        this.resultingBody = Objects.requireNonNull(
                resultingBody, "resultingBody").clone();
        this.emittedEvents = immutableNodes(emittedEvents);
        this.orderedPatches = immutablePatches(orderedPatches);
        this.gasBefore = ClosureValueSupport.requireSafeInteger(
                gasBefore, "gasBefore");
        this.gasAfter = ClosureValueSupport.requireSafeInteger(
                gasAfter, "gasAfter");
        if (this.gasAfter < this.gasBefore) {
            throw new IllegalArgumentException("gasAfter precedes gasBefore");
        }
        this.identityAffecting = identityAffecting;
        this.transitionEvidence = transitionEvidence;
    }

    /**
     * Compatibility constructor for producers with no applied local patches.
     *
     * @param documentId target managed document
     * @param workOccurrenceIdentity exact owning work identity
     * @param beforeBlueId exact target identity before execution
     * @param resultingBody resulting local body before identity finalization
     * @param emittedEvents ordered locally emitted events
     * @param gasBefore shared gas total before execution
     * @param gasAfter shared gas total after execution
     * @param identityAffecting whether identity reconciliation is required
     */
    public LocalDocumentStepResult(
            DocumentId documentId,
            String workOccurrenceIdentity,
            String beforeBlueId,
            Node resultingBody,
            List<Node> emittedEvents,
            long gasBefore,
            long gasAfter,
            boolean identityAffecting) {
        this(documentId,
                workOccurrenceIdentity,
                beforeBlueId,
                resultingBody,
                emittedEvents,
                Collections.<FrozenJsonPatch>emptyList(),
                gasBefore,
                gasAfter,
                identityAffecting);
    }

    /** Returns the target managed document.
     * @return document identity */
    public DocumentId documentId() { return documentId; }

    /** Returns the exact owning work identity.
     * @return work identity */
    public String workOccurrenceIdentity() { return workOccurrenceIdentity; }

    /** Returns the exact pre-step identity.
     * @return before BlueId */
    public String beforeBlueId() { return beforeBlueId; }

    /** Returns a defensive local body copy.
     * @return resulting body */
    public Node resultingBody() { return resultingBody.clone(); }

    /** Returns defensive emitted-event copies.
     * @return ordered events */
    public List<Node> emittedEvents() { return immutableNodes(emittedEvents); }

    /** Returns exact immutable patches in their applied order.
     * @return ordered applied patches */
    public List<FrozenJsonPatch> orderedPatches() { return orderedPatches; }

    /** Returns the pre-step shared gas total.
     * @return admitted gas before */
    public long gasBefore() { return gasBefore; }

    /** Returns the post-step shared gas total.
     * @return admitted gas after */
    public long gasAfter() { return gasAfter; }

    /** Reports whether identity reconciliation is required.
     * @return flag */
    public boolean identityAffecting() { return identityAffecting; }

    /**
     * Returns optional non-identity-bearing processor presentation evidence.
     * Custom document-step processors using the compatibility constructors do
     * not manufacture this evidence.
     */
    Optional<DocumentTransitionEvidence> transitionEvidence() {
        return Optional.ofNullable(transitionEvidence);
    }

    private static List<Node> immutableNodes(List<Node> values) {
        ArrayList<Node> copy = new ArrayList<Node>();
        for (Node value : Objects.requireNonNull(values, "emittedEvents")) {
            copy.add(Objects.requireNonNull(value, "emitted event").clone());
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<FrozenJsonPatch> immutablePatches(
            List<FrozenJsonPatch> values) {
        ArrayList<FrozenJsonPatch> copy =
                new ArrayList<FrozenJsonPatch>();
        for (FrozenJsonPatch value
                : Objects.requireNonNull(values, "orderedPatches")) {
            copy.add(Objects.requireNonNull(value, "ordered patch"));
        }
        return Collections.unmodifiableList(copy);
    }
}
