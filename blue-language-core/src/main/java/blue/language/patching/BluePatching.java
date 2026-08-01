package blue.language.patching;

import blue.language.model.Node;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.merge.ResolvedSnapshot;

/** Applies Language-owned patches to canonical inputs and snapshots. */
public interface BluePatching {

    /** Applies one patch to exact canonical input. */
    CanonicalPatchResult apply(Node canonicalIdentityInput, BluePatch patch);

    /** Applies one patch and completely resolves the resulting snapshot. */
    ResolvedSnapshot apply(ResolvedSnapshot snapshot, BluePatch patch);
}
