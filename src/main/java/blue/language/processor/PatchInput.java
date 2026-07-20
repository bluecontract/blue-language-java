package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One defensively captured mutable or already-frozen authored patch. */
final class PatchInput {

    private final JsonPatch mutablePatch;
    private final FrozenJsonPatch frozenPatch;

    private PatchInput(JsonPatch mutablePatch, FrozenJsonPatch frozenPatch) {
        this.mutablePatch = mutablePatch;
        this.frozenPatch = frozenPatch;
    }

    static PatchInput mutable(JsonPatch patch) {
        return patch == null ? null : new PatchInput(ImmutableJsonPatch.copy(patch), null);
    }

    static PatchInput frozen(FrozenJsonPatch patch) {
        return patch == null ? null : new PatchInput(null, patch);
    }

    static List<PatchInput> mutableList(List<JsonPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        List<PatchInput> captured = new ArrayList<>(patches.size());
        for (JsonPatch patch : patches) {
            captured.add(mutable(patch));
        }
        return Collections.unmodifiableList(captured);
    }

    static List<PatchInput> frozenList(List<FrozenJsonPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        List<PatchInput> captured = new ArrayList<>(patches.size());
        for (FrozenJsonPatch patch : patches) {
            captured.add(frozen(patch));
        }
        return Collections.unmodifiableList(captured);
    }

    JsonPatch.Op op() {
        return mutablePatch != null ? mutablePatch.getOp() : frozenPatch.getOp();
    }

    String authoredPath() {
        return mutablePatch != null ? mutablePatch.getPath() : frozenPatch.getPath();
    }

    boolean isFrozen() {
        return frozenPatch != null;
    }

    Node mutableValue() {
        return mutablePatch != null ? mutablePatch.getVal() : null;
    }

    FrozenNode frozenValue() {
        return frozenPatch != null ? frozenPatch.getValue() : null;
    }

    long frozenAuthoredCanonicalSizeBytes() {
        if (frozenPatch == null) {
            throw new IllegalStateException("Mutable patch inputs do not carry frozen authored size");
        }
        return frozenPatch.getAuthoredCanonicalSizeBytes();
    }

    JsonPatch legacyPatch() {
        if (mutablePatch == null) {
            throw new IllegalStateException("Frozen patch inputs do not expose a mutable validation patch");
        }
        return mutablePatch;
    }

    ImmutableJsonPatch prepare(ImmutableJsonPatch.PreparationContext context,
                               FrozenNode canonicalRoot,
                               FrozenNode resolvedRoot) {
        return mutablePatch != null
                ? context.prepare(mutablePatch, canonicalRoot, resolvedRoot)
                : context.prepare(frozenPatch, canonicalRoot, resolvedRoot);
    }
}
