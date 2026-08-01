package blue.language.api.internal;

import blue.language.Blue;
import blue.language.BlueOperationLimits;
import blue.language.BlueOperationOutcome;
import blue.language.BlueOperationResult;
import blue.language.matching.BlueMatching;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Objects;

import static blue.language.utils.Properties.OBJECT_BLUE;

/** Focused matching adapter over the compatibility runtime. */
public final class LegacyBlueMatching implements BlueMatching {

    private final Blue blue;

    public LegacyBlueMatching(Blue blue) {
        this.blue = Objects.requireNonNull(blue, OBJECT_BLUE);
    }

    @Override
    public boolean matches(Node candidate, Node type) {
        return blue.nodeMatchesType(candidate, type);
    }

    @Override
    public boolean matches(FrozenNode candidate, FrozenNode type) {
        return blue.nodeMatchesType(candidate, type);
    }

    @Override
    public boolean matches(
            ResolvedSnapshot snapshot, String pointer, FrozenNode type) {
        return blue.nodeMatchesType(snapshot, pointer, type);
    }

    @Override
    public BlueOperationResult<Boolean> matchesLimited(
            Node candidate, Node type, BlueOperationLimits limits) {
        BlueOperationResult<Node> resolved =
                blue.resolveLimited(candidate, limits);
        if (resolved.outcome() == BlueOperationOutcome.ESTABLISHED) {
            return BlueOperationResult.established(
                    blue.nodeMatchesType(
                            resolved.requireEstablished(), type));
        }
        if (resolved.outcome() == BlueOperationOutcome.ABSENT) {
            return BlueOperationResult.absent(
                    resolved.reason().orElse(null));
        }
        if (resolved.outcome() == BlueOperationOutcome.INCOMPLETE) {
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
