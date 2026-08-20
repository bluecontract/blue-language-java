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
    private final long gasBefore;
    private final long gasAfter;
    private final boolean identityAffecting;

    ManagedDocumentStepOutcome(
            Node resultingBody,
            List<Node> emittedEvents,
            List<FrozenJsonPatch> orderedPatches,
            long gasBefore,
            long gasAfter,
            boolean identityAffecting) {
        this.resultingBody = Objects.requireNonNull(
                resultingBody, "resultingBody").clone();
        this.emittedEvents = immutableNodes(emittedEvents);
        this.orderedPatches = Collections.unmodifiableList(
                new ArrayList<FrozenJsonPatch>(Objects.requireNonNull(
                        orderedPatches, "orderedPatches")));
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
