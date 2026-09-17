package blue.language.runtime;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.matching.BlueMatching;
import blue.language.matching.MatchingRuntime;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.matching.NodeTypeMatcher;
import blue.language.resolve.ResolutionLimits;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Shared focused matching implementation for core and compatibility hosts.
 */
public final class LanguageMatchingService implements BlueMatching {

    private final MatchingRuntime runtime;
    private final ResolutionLimits defaultLimits;

    /**
     * Creates a matching service whose runtime owns limited matching evidence.
     *
     * @param runtime runtime used for resolution and operation-owned matching
     * @param defaultLimits limits applied by complete mutable matching
     */
    public LanguageMatchingService(
            MatchingRuntime runtime,
            ResolutionLimits defaultLimits) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.defaultLimits = Objects.requireNonNull(defaultLimits, "defaultLimits");
    }

    /**
     * Creates a matching service with explicit resolution dependencies.
     *
     * @param runtime runtime used for preprocessing, resolution, and type lookup
     * @param defaultLimits limits applied by complete mutable matching
     * @param limitedResolver legacy candidate resolver, retained for compatibility;
     *                        the runtime now owns the entire limited match
     * @throws NullPointerException if any argument is {@code null}
     * @deprecated use the constructor with runtime and default limits
     */
    @Deprecated
    public LanguageMatchingService(
            MatchingRuntime runtime,
            ResolutionLimits defaultLimits,
            BiFunction<Node, BlueOperationLimits,
                    BlueOperationResult<Node>> limitedResolver) {
        this(runtime, defaultLimits);
        Objects.requireNonNull(limitedResolver, "limitedResolver");
    }

    /**
     * Resolves and tests whether an authored candidate matches a type.
     *
     * @param candidate authored candidate value
     * @param type authored type definition
     * @return whether the resolved candidate matches the resolved type; runtime
     *         matching failures return {@code false}
     */
    @Override
    public boolean matches(Node candidate, Node type) {
        return new NodeTypeMatcher(runtime).matchesType(
                candidate, type, defaultLimits);
    }

    /**
     * Tests two already-resolved immutable values.
     *
     * @param candidate resolved immutable candidate
     * @param type resolved immutable type definition
     * @return whether {@code candidate} matches {@code type}
     */
    @Override
    public boolean matches(FrozenNode candidate, FrozenNode type) {
        return new NodeTypeMatcher(runtime).matchesResolvedType(
                candidate, type);
    }

    /**
     * Tests one resolved snapshot path against an immutable type.
     *
     * @param snapshot resolved snapshot containing the candidate
     * @param pointer RFC 6901 pointer selecting the candidate
     * @param type resolved immutable type definition
     * @return whether the selected candidate matches {@code type}
     */
    @Override
    public boolean matches(
            ResolvedSnapshot snapshot,
            String pointer,
            FrozenNode type) {
        return new NodeTypeMatcher(runtime).matchesResolvedType(
                snapshot, pointer, type);
    }

    /**
     * Performs a demand-limited match with an exhaustive outcome.
     *
     * @param candidate authored candidate value
     * @param type authored type definition
     * @param limits semantic-demand and reference-expansion limits
     * @return established match result or the runtime's explicit absent,
     *         incomplete, or invalid outcome
     */
    @Override
    public BlueOperationResult<Boolean> matchesLimited(
            Node candidate,
            Node type,
            BlueOperationLimits limits) {
        return runtime.matchesLimitedForMatching(candidate, type, limits);
    }
}
