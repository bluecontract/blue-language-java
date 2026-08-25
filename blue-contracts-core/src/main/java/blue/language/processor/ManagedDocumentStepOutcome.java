package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pre-finalization processor-side outcome for one isolated local step. */
public final class ManagedDocumentStepOutcome {

    private final Node resultingBody;
    private final List<Node> emittedEvents;
    private final List<FrozenJsonPatch> orderedPatches;
    private final List<DocumentUpdateOccurrence> orderedPatchUpdates;
    private final String beforeEffectiveTypeBlueId;
    private final String afterEffectiveTypeBlueId;
    private final List<ManagedGeneralizationWrite>
            generatedGeneralizationWrites;
    private final long gasBefore;
    private final long gasAfter;
    private final boolean identityAffecting;

    ManagedDocumentStepOutcome(
            Node resultingBody,
            List<Node> emittedEvents,
            List<FrozenJsonPatch> orderedPatches,
            List<DocumentUpdateOccurrence> orderedPatchUpdates,
            String beforeEffectiveTypeBlueId,
            String afterEffectiveTypeBlueId,
            List<ManagedGeneralizationWrite> generatedGeneralizationWrites,
            long gasBefore,
            long gasAfter,
            boolean identityAffecting) {
        this.resultingBody = Objects.requireNonNull(
                resultingBody, "resultingBody").clone();
        this.emittedEvents = immutableNodes(emittedEvents);
        this.orderedPatches = Collections.unmodifiableList(
                new ArrayList<FrozenJsonPatch>(Objects.requireNonNull(
                        orderedPatches, "orderedPatches")));
        this.orderedPatchUpdates = Collections.unmodifiableList(
                new ArrayList<DocumentUpdateOccurrence>(
                        Objects.requireNonNull(
                                orderedPatchUpdates,
                                "orderedPatchUpdates")));
        if (this.orderedPatchUpdates.size() != this.orderedPatches.size()) {
            throw new IllegalArgumentException(
                    "Every authored patch requires one exact update transition");
        }
        this.beforeEffectiveTypeBlueId = beforeEffectiveTypeBlueId;
        this.afterEffectiveTypeBlueId = afterEffectiveTypeBlueId;
        this.generatedGeneralizationWrites = Collections.unmodifiableList(
                new ArrayList<ManagedGeneralizationWrite>(
                        Objects.requireNonNull(
                                generatedGeneralizationWrites,
                                "generatedGeneralizationWrites")));
        this.gasBefore = gasBefore;
        this.gasAfter = gasAfter;
        if (gasBefore < 0L || gasAfter < gasBefore) {
            throw new IllegalArgumentException("Invalid shared gas interval");
        }
        this.identityAffecting = identityAffecting;
    }

    /**
     * Returns the exact local body after this step.
     *
     * @return defensive resulting local body
     */
    public Node resultingBody() { return resultingBody.clone(); }

    /**
     * Returns every application event emitted by this local step.
     *
     * @return defensive locally emitted event copies
     */
    public List<Node> emittedEvents() { return immutableNodes(emittedEvents); }

    /**
     * Returns the authored patches already applied by this local step.
     *
     * @return immutable exact authored patches in applied order
     */
    public List<FrozenJsonPatch> orderedPatches() { return orderedPatches; }

    /**
     * Returns one exact before/after occurrence per authored patch.
     *
     * @return immutable occurrences in authored patch order
     */
    public List<DocumentUpdateOccurrence> orderedPatchUpdates() {
        return orderedPatchUpdates;
    }

    /**
     * Returns the admitted effective Root type BlueId, or {@code null}.
     *
     * @return admitted effective type identity, or {@code null}
     */
    public String beforeEffectiveTypeBlueId() {
        return beforeEffectiveTypeBlueId;
    }

    /**
     * Returns the resulting effective Root type BlueId, or {@code null}.
     *
     * @return resulting effective type identity, or {@code null}
     */
    public String afterEffectiveTypeBlueId() {
        return afterEffectiveTypeBlueId;
    }

    /**
     * Returns processor-generated generalization writes in commit order.
     *
     * @return immutable committed write evidence
     */
    public List<ManagedGeneralizationWrite> generatedGeneralizationWrites() {
        return generatedGeneralizationWrites;
    }

    /**
     * Returns the shared ledger total immediately before this step.
     *
     * @return shared gas total before this step
     */
    public long gasBefore() { return gasBefore; }

    /**
     * Returns the shared ledger total immediately after this step.
     *
     * @return shared gas total after this step
     */
    public long gasAfter() { return gasAfter; }

    /**
     * Reports whether the local body changed semantically.
     *
     * @return whether local body identity reconciliation is required
     */
    public boolean identityAffecting() { return identityAffecting; }

    private static List<Node> immutableNodes(List<Node> values) {
        ArrayList<Node> copy = new ArrayList<Node>();
        for (Node value : Objects.requireNonNull(values, "emittedEvents")) {
            copy.add(Objects.requireNonNull(value, "emittedEvent").clone());
        }
        return Collections.unmodifiableList(copy);
    }
}
