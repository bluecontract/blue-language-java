package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One defensively captured authored, resolved-origin, or already-frozen patch.
 *
 * <p>The source label remains attached for trace attribution. Conversion to
 * {@link ImmutableJsonPatch} snapshots mutable input exactly once and can then
 * be reused by preview and commit planning.</p>
 */
final class PatchInput {

    private final JsonPatch mutablePatch;
    private final FrozenJsonPatch frozenPatch;
    private final PatchSource source;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;

    private PatchInput(
            JsonPatch mutablePatch,
            FrozenJsonPatch frozenPatch,
            PatchSource source,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this.mutablePatch = mutablePatch;
        this.frozenPatch = frozenPatch;
        this.source = source != null ? source : PatchSource.UNKNOWN_INTERNAL;
        this.canonicalTypeIdentities = canonicalTypeIdentities;
    }

    static PatchInput mutable(JsonPatch patch) {
        return mutable(patch, PatchSource.LEGACY_PUBLIC_API);
    }

    static PatchInput mutable(JsonPatch patch, PatchSource source) {
        if (patch == null) {
            return null;
        }
        JsonPatch captured = ImmutableJsonPatch.copy(patch);
        return new PatchInput(captured, null, source, null);
    }

    static PatchInput resolved(
            JsonPatch patch,
            PatchSource source,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        if (patch == null) {
            return null;
        }
        CanonicalTypeIdentityLookup identities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        JsonPatch captured = ImmutableJsonPatch.copy(patch);
        if (captured.getOp() != JsonPatch.Op.REMOVE) {
            Node projected = CanonicalEffectSourceProjection
                    .projectResolvedPatchValue(
                            captured.getVal(),
                            captured.getPath(),
                            identities);
            captured = withValue(captured, projected);
        }
        return new PatchInput(
                captured,
                null,
                source,
                identities);
    }

    private static JsonPatch withValue(
            JsonPatch patch,
            Node projectedValue) {
        switch (patch.getOp()) {
            case ADD:
                return JsonPatch.add(patch.getPath(), projectedValue);
            case REPLACE:
                return JsonPatch.replace(patch.getPath(), projectedValue);
            case REMOVE:
                return patch;
            default:
                throw new IllegalStateException(
                        "Unsupported patch op: " + patch.getOp());
        }
    }

    static PatchInput frozen(FrozenJsonPatch patch) {
        return patch == null
                ? null
                : new PatchInput(
                        null,
                        patch,
                        PatchSource.UNKNOWN_INTERNAL,
                        null);
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

    static List<PatchInput> resolvedList(
            List<JsonPatch> patches,
            PatchSource source,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        List<PatchInput> captured = new ArrayList<>(patches.size());
        for (JsonPatch patch : patches) {
            captured.add(resolved(
                    patch, source, canonicalTypeIdentities));
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

    CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return canonicalTypeIdentities;
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

    /** Returns a detached copy of the exact Source patch captured at admission. */
    JsonPatch detachedSourcePatch() {
        return ImmutableJsonPatch.copy(legacyPatch());
    }

    ImmutableJsonPatch prepare(ImmutableJsonPatch.PreparationContext context,
                               FrozenNode canonicalRoot,
                               FrozenNode resolvedRoot) {
        return mutablePatch != null
                ? context.prepare(mutablePatch, canonicalRoot, resolvedRoot, source)
                : context.prepare(frozenPatch, canonicalRoot, resolvedRoot);
    }
}
