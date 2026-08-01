package blue.language.matching;

import blue.language.BlueOperationLimits;
import blue.language.BlueOperationResult;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;

/** Type and structural matching over mutable or immutable Language values. */
public interface BlueMatching {

    /** Resolves and tests whether an authored candidate matches a type. */
    boolean matches(Node candidate, Node type);

    /** Tests two already-resolved immutable values. */
    boolean matches(FrozenNode candidate, FrozenNode type);

    /** Tests one resolved snapshot path against an immutable type. */
    boolean matches(
            ResolvedSnapshot snapshot, String pointer, FrozenNode type);

    /** Performs a demand-limited match with an exhaustive outcome. */
    BlueOperationResult<Boolean> matchesLimited(
            Node candidate, Node type, BlueOperationLimits limits);
}
