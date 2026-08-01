package blue.language.runtime;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.matching.BlueMatching;
import blue.language.matching.MatchingRuntime;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.matching.NodeTypeMatcher;
import blue.language.utils.limits.Limits;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Shared focused matching implementation for core and compatibility hosts.
 */
public final class LanguageMatchingService implements BlueMatching {

    private final MatchingRuntime runtime;
    private final Limits defaultLimits;
    private final BiFunction<Node, BlueOperationLimits,
            BlueOperationResult<Node>> limitedResolver;

    public LanguageMatchingService(
            MatchingRuntime runtime,
            Limits defaultLimits,
            BiFunction<Node, BlueOperationLimits,
                    BlueOperationResult<Node>> limitedResolver) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.defaultLimits = Objects.requireNonNull(
                defaultLimits, "defaultLimits");
        this.limitedResolver = Objects.requireNonNull(
                limitedResolver, "limitedResolver");
    }

    @Override
    public boolean matches(Node candidate, Node type) {
        return new NodeTypeMatcher(runtime).matchesType(
                candidate, type, defaultLimits);
    }

    @Override
    public boolean matches(FrozenNode candidate, FrozenNode type) {
        return new NodeTypeMatcher(runtime).matchesResolvedType(
                candidate, type);
    }

    @Override
    public boolean matches(
            ResolvedSnapshot snapshot,
            String pointer,
            FrozenNode type) {
        return new NodeTypeMatcher(runtime).matchesResolvedType(
                snapshot, pointer, type);
    }

    @Override
    public BlueOperationResult<Boolean> matchesLimited(
            Node candidate,
            Node type,
            BlueOperationLimits limits) {
        BlueOperationResult<Node> resolved = limitedResolver.apply(
                candidate, limits);
        if (resolved.outcome()
                == BlueOperationOutcome.ESTABLISHED) {
            return BlueOperationResult.established(
                    matches(resolved.requireEstablished(), type));
        }
        if (resolved.outcome() == BlueOperationOutcome.ABSENT) {
            return BlueOperationResult.absent(
                    resolved.reason().orElse(null));
        }
        if (resolved.outcome()
                == BlueOperationOutcome.INCOMPLETE) {
            return BlueOperationResult.incomplete(
                    null,
                    resolved.outstandingBlueIds(),
                    resolved.providerOutcome().orElse(null),
                    resolved.reason().orElse(null));
        }
        return BlueOperationResult.invalid(
                resolved.reason().orElse(null),
                resolved.providerOutcome().orElse(null));
    }
}
