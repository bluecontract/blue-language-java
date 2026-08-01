package blue.language.patching;

import blue.language.model.Node;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.merge.ResolvedSnapshot;

/** Applies Language-owned patches to canonical inputs and snapshots. */
public interface BluePatching {

    /**
     * Applies one patch to exact canonical input.
     *
     * @param canonicalIdentityInput exact canonical identity input to patch
     * @param patch immutable patch operation
     * @return canonical result describing the applied operation
     */
    CanonicalPatchResult apply(Node canonicalIdentityInput, BluePatch patch);

    /**
     * Applies one patch and completely resolves the resulting snapshot.
     *
     * @param snapshot immutable snapshot to patch
     * @param patch immutable patch operation
     * @return completely resolved patched snapshot
     */
    ResolvedSnapshot apply(ResolvedSnapshot snapshot, BluePatch patch);
}
