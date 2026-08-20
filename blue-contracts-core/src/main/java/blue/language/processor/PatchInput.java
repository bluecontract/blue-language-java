package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One defensively captured mutable or already-frozen authored patch.
 *
 * <p>The source label remains attached for trace attribution. Conversion to
 * {@link ImmutableJsonPatch} snapshots mutable input exactly once and can then
 * be reused by preview and commit planning.</p>
 */
final class PatchInput {

    private final JsonPatch mutablePatch;
    private final FrozenJsonPatch frozenPatch;
    private final PatchSource source;

    private PatchInput(JsonPatch mutablePatch, FrozenJsonPatch frozenPatch, PatchSource source) {
        this.mutablePatch = mutablePatch;
        this.frozenPatch = frozenPatch;
        this.source = source != null ? source : PatchSource.UNKNOWN_INTERNAL;
    }

    static PatchInput mutable(JsonPatch patch) {
        return mutable(patch, PatchSource.LEGACY_PUBLIC_API);
    }

    static PatchInput mutable(JsonPatch patch, PatchSource source) {
        return patch == null
                ? null
                : new PatchInput(ImmutableJsonPatch.copy(patch), null, source);
    }

    static PatchInput frozen(FrozenJsonPatch patch) {
        return patch == null
                ? null
                : new PatchInput(null, patch, PatchSource.UNKNOWN_INTERNAL);
    }

    static List<PatchInput> mutableList(List<JsonPatch> patches) {
        return mutableList(patches, PatchSource.LEGACY_PUBLIC_API);
    }

    static List<PatchInput> mutableList(List<JsonPatch> patches, PatchSource source) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        List<PatchInput> captured = new ArrayList<>(patches.size());
        for (JsonPatch patch : patches) {
            captured.add(mutable(patch, source));
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

    PatchSource source() {
        return source;
    }

    Node mutableValue() {
        return mutablePatch != null ? mutablePatch.getVal() : null;
    }

    FrozenNode frozenValue() {
        return frozenPatch != null ? frozenPatch.getValue() : null;
    }

    ExactBlueValue exactValue() {
        return frozenPatch != null
                ? frozenPatch.getExactValue()
                : null;
    }

    long frozenAuthoredCanonicalSizeBytes() {
        if (frozenPatch == null) {
            throw new IllegalStateException("Mutable patch inputs do not carry frozen authored size");
        }
        return frozenPatch.getAuthoredCanonicalSizeBytes();
    }

    /** Returns the exact immutable authored patch retained by this input. */
    FrozenJsonPatch frozenAuthoredPatch() {
        return frozenPatch != null
                ? frozenPatch
                : FrozenJsonPatch.from(mutablePatch);
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
                ? context.prepare(mutablePatch, canonicalRoot, resolvedRoot, source)
                : context.prepare(frozenPatch, canonicalRoot, resolvedRoot);
    }
}
