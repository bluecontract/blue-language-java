package blue.language.processor;

import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class BatchPatchResult {

    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final List<DocumentProcessingRuntime.DocumentUpdateData> updates;
    private final long patchPlanningNanos;
    private final long conformanceNanos;
    private final long buildUpdatesNanos;

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentProcessingRuntime.DocumentUpdateData> updates) {
        this(canonicalRoot, resolvedRoot, updates, 0L, 0L, 0L);
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentProcessingRuntime.DocumentUpdateData> updates,
                     long patchPlanningNanos,
                     long conformanceNanos,
                     long buildUpdatesNanos) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        this.updates = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(updates, "updates")));
        this.patchPlanningNanos = patchPlanningNanos;
        this.conformanceNanos = conformanceNanos;
        this.buildUpdatesNanos = buildUpdatesNanos;
    }

    FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    List<DocumentProcessingRuntime.DocumentUpdateData> updates() {
        return updates;
    }

    long patchPlanningNanos() {
        return patchPlanningNanos;
    }

    long conformanceNanos() {
        return conformanceNanos;
    }

    long buildUpdatesNanos() {
        return buildUpdatesNanos;
    }
}
