package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A pure canonical-origin evaluation: independent atomic operations or noncommitting exact needs. */
public final class SameOriginProcessAttempt {
    private final List<SameOriginOperationResult> operations;
    private final ClosureAttemptResult needs;

    private SameOriginProcessAttempt(List<SameOriginOperationResult> operations, ClosureAttemptResult needs) {
        this.operations = Collections.unmodifiableList(new ArrayList<>(operations)); this.needs = needs;
    }

    static SameOriginProcessAttempt complete(List<SameOriginOperationResult> operations) {
        return new SameOriginProcessAttempt(Objects.requireNonNull(operations), null);
    }

    static SameOriginProcessAttempt needs(ClosureAttemptResult needs) {
        if (Objects.requireNonNull(needs).kind() == ClosureAttemptResult.Kind.COMPLETE)
            throw new IllegalArgumentException("A same-origin need cannot contain an atomic closure result");
        return new SameOriginProcessAttempt(Collections.emptyList(), needs);
    }

    public boolean complete() { return needs == null; }
    /** Dependency-first deterministic order. Each entry has its own transaction/receipt identity. */
    public List<SameOriginOperationResult> operations() { return operations; }
    public List<ClosureResourceDemand> resourceDemands() { return needs == null ? Collections.emptyList() : needs.resourceDemands(); }
    public List<String> requiredExactBlueIds() { return needs == null ? Collections.emptyList() : needs.requiredExactBlueIds(); }
}
