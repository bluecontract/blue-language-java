package blue.language.matching;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;

/** Type and structural matching over mutable or immutable Language values. */
public interface BlueMatching {

    /**
     * Resolves and tests whether an authored candidate matches a type.
     *
     * @param candidate authored candidate value
     * @param type authored type definition
     * @return whether the resolved candidate matches the resolved type
     */
    boolean matches(Node candidate, Node type);

    /**
     * Tests two already-resolved immutable values.
     *
     * @param candidate resolved immutable candidate
     * @param type resolved immutable type definition
     * @return whether {@code candidate} matches {@code type}
     */
    boolean matches(FrozenNode candidate, FrozenNode type);

    /**
     * Tests one resolved snapshot path against an immutable type.
     *
     * @param snapshot resolved snapshot containing the candidate
     * @param pointer RFC 6901 pointer selecting the candidate
     * @param type resolved immutable type definition
     * @return whether the selected candidate matches {@code type}
     */
    boolean matches(
            ResolvedSnapshot snapshot, String pointer, FrozenNode type);

    /**
     * Performs a demand-limited match with an exhaustive outcome.
     *
     * @param candidate authored candidate value
     * @param type authored type definition
     * @param limits semantic-demand and reference-expansion limits
     * @return established match result or an explicit non-established outcome
     */
    BlueOperationResult<Boolean> matchesLimited(
            Node candidate, Node type, BlueOperationLimits limits);
}
