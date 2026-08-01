package blue.language.api.internal;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.patching.BluePatch;
import blue.language.patching.BluePatching;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Objects;

import static blue.language.utils.Properties.OBJECT_BLUE;

/** Focused patching adapter over the compatibility runtime. */
public final class LegacyBluePatching implements BluePatching {

    private final Blue blue;

    public LegacyBluePatching(Blue blue) {
        this.blue = Objects.requireNonNull(blue, OBJECT_BLUE);
    }

    @Override
    public CanonicalPatchResult apply(
            Node canonicalIdentityInput, BluePatch patch) {
        return blue.applyCanonicalPatch(
                canonicalIdentityInput, patch);
    }

    @Override
    public ResolvedSnapshot apply(
            ResolvedSnapshot snapshot, BluePatch patch) {
        return blue.applyCanonicalPatch(snapshot, patch);
    }
}
