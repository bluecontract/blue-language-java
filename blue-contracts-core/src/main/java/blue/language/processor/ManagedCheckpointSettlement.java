package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Atomic Root-local checkpoint marker result at the quiescence barrier. */
public final class ManagedCheckpointSettlement {

    private final Node resultingBody;
    private final List<ManagedCheckpointMutation> mutations;
    private final long gasBefore;
    private final long gasAfter;

    ManagedCheckpointSettlement(
            Node resultingBody,
            List<ManagedCheckpointMutation> mutations,
            long gasBefore,
            long gasAfter) {
        this.resultingBody = Objects.requireNonNull(
                resultingBody, "resultingBody").clone();
        this.mutations = Collections.unmodifiableList(
                new ArrayList<ManagedCheckpointMutation>(
                        Objects.requireNonNull(mutations, "mutations")));
        if (gasBefore < 0L || gasAfter < gasBefore) {
            throw new IllegalArgumentException("Invalid shared gas interval");
        }
        this.gasBefore = gasBefore;
        this.gasAfter = gasAfter;
    }

    /**
     * Returns the exact Root body after the whole marker batch.
     *
     * @return detached resulting Root
     */
    public Node resultingBody() { return resultingBody.clone(); }

    /**
     * Returns actual writes in raw-source order followed by lexical cleanup.
     *
     * @return immutable canonical mutation sequence
     */
    public List<ManagedCheckpointMutation> mutations() { return mutations; }

    /**
     * Returns the shared ledger total on barrier entry.
     *
     * @return exact gas total before settlement
     */
    public long gasBefore() { return gasBefore; }

    /**
     * Returns the shared ledger total after the successful whole barrier.
     *
     * @return exact gas total after settlement
     */
    public long gasAfter() { return gasAfter; }
}
