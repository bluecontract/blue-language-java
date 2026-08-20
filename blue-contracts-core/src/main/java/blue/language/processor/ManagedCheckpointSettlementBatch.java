package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of one closure-wide atomic direct-marker settlement batch.
 *
 * <p>Mutations are exposed once in their exact global application order.
 * Target results retain request order solely for deterministic body lookup.</p>
 */
public final class ManagedCheckpointSettlementBatch {

    private final List<TargetResult> targets;
    private final List<Mutation> mutations;
    private final long gasBefore;
    private final long gasAfter;

    ManagedCheckpointSettlementBatch(
            List<TargetResult> targets,
            List<Mutation> mutations,
            long gasBefore,
            long gasAfter) {
        this.targets = immutableCopy(targets, "targets");
        this.mutations = immutableCopy(mutations, "mutations");
        if (gasBefore < 0L || gasAfter < gasBefore) {
            throw new IllegalArgumentException("Invalid shared gas interval");
        }
        this.gasBefore = gasBefore;
        this.gasAfter = gasAfter;
    }

    /**
     * Returns resulting target Roots in request order.
     *
     * @return immutable target-result sequence
     */
    public List<TargetResult> targets() {
        return targets;
    }

    /**
     * Returns actual writes in global raw-source order followed by cleanup in
     * lexical target-scope/key order.
     *
     * @return immutable exact global mutation sequence
     */
    public List<Mutation> mutations() {
        return mutations;
    }

    /**
     * Returns the shared ledger total on batch entry.
     *
     * @return exact gas total before settlement
     */
    public long gasBefore() {
        return gasBefore;
    }

    /**
     * Returns the shared ledger total after the successful whole batch.
     *
     * @return exact gas total after settlement
     */
    public long gasAfter() {
        return gasAfter;
    }

    private static <T> List<T> immutableCopy(
            List<T> values,
            String label) {
        ArrayList<T> copy = new ArrayList<T>();
        for (T value : Objects.requireNonNull(values, label)) {
            copy.add(Objects.requireNonNull(value, label + " item"));
        }
        return Collections.unmodifiableList(copy);
    }

    /** One exact resulting Root paired with its target identity. */
    public static final class TargetResult {

        private final String targetManagedScopeIdentity;
        private final Node resultingBody;

        TargetResult(
                String targetManagedScopeIdentity,
                Node resultingBody) {
            if (targetManagedScopeIdentity == null
                    || targetManagedScopeIdentity.isEmpty()) {
                throw new IllegalArgumentException(
                        "targetManagedScopeIdentity must be non-empty");
            }
            this.targetManagedScopeIdentity = targetManagedScopeIdentity;
            this.resultingBody = Objects.requireNonNull(
                    resultingBody, "resultingBody").clone();
        }

        /**
         * Returns the exact target managed-scope identity.
         *
         * @return non-empty target identity
         */
        public String targetManagedScopeIdentity() {
            return targetManagedScopeIdentity;
        }

        /**
         * Returns the exact Root after the complete batch defensively.
         *
         * @return detached resulting Root
         */
        public Node resultingBody() {
            return resultingBody.clone();
        }
    }

    /** One actual mutation paired with its exact target identity. */
    public static final class Mutation {

        private final String targetManagedScopeIdentity;
        private final ManagedCheckpointMutation mutation;

        Mutation(
                String targetManagedScopeIdentity,
                ManagedCheckpointMutation mutation) {
            if (targetManagedScopeIdentity == null
                    || targetManagedScopeIdentity.isEmpty()) {
                throw new IllegalArgumentException(
                        "targetManagedScopeIdentity must be non-empty");
            }
            this.targetManagedScopeIdentity = targetManagedScopeIdentity;
            this.mutation = Objects.requireNonNull(mutation, "mutation");
        }

        /**
         * Returns the exact target managed-scope identity.
         *
         * @return non-empty target identity
         */
        public String targetManagedScopeIdentity() {
            return targetManagedScopeIdentity;
        }

        /**
         * Returns the exact applied mutation evidence.
         *
         * @return immutable add, replace, or removal evidence
         */
        public ManagedCheckpointMutation mutation() {
            return mutation;
        }
    }
}
