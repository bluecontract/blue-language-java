package blue.language.conformance;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of planning type generalization after one or more changed
 * paths.
 *
 * <p>The plan keeps the next resolved root, optional canonical root, exact
 * canonical replacements, and all metadata paths changed by widening.
 * Collections are defensive unmodifiable copies.</p>
 */
public final class ConformancePlan {

    private final FrozenNode canonicalRoot;
    private final FrozenNode root;
    private final boolean generalized;
    private final List<CanonicalGeneralizationPatch> canonicalPatches;
    private final List<String> changedPaths;
    private final boolean fullSnapshotRebuildAvoidable;

    ConformancePlan(FrozenNode root, boolean generalized) {
        this(null, root, generalized, Collections.emptyList(), Collections.emptyList(), false);
    }

    ConformancePlan(FrozenNode canonicalRoot,
                    FrozenNode root,
                    boolean generalized,
                    List<CanonicalGeneralizationPatch> canonicalPatches,
                    List<String> changedPaths,
                    boolean fullSnapshotRebuildAvoidable) {
        this.canonicalRoot = canonicalRoot;
        this.root = Objects.requireNonNull(root, "root");
        this.generalized = generalized;
        this.canonicalPatches = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(canonicalPatches, "canonicalPatches")));
        this.changedPaths = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(changedPaths, "changedPaths")));
        this.fullSnapshotRebuildAvoidable = fullSnapshotRebuildAvoidable;
    }

    /**
     * Creates an unchanged plan containing only a resolved root.
     *
     * @param root immutable resolved root
     * @return unchanged plan without a canonical root
     * @throws NullPointerException when {@code root} is null
     */
    public static ConformancePlan unchanged(FrozenNode root) {
        return new ConformancePlan(root, false);
    }

    /**
     * Creates an unchanged plan retaining canonical and resolved roots.
     *
     * @param canonicalRoot immutable canonical root, or {@code null} when
     *                      unavailable
     * @param root immutable resolved root
     * @return unchanged plan
     * @throws NullPointerException when {@code root} is null
     */
    public static ConformancePlan unchanged(FrozenNode canonicalRoot, FrozenNode root) {
        return new ConformancePlan(canonicalRoot,
                root,
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                canonicalRoot != null);
    }

    /**
     * Creates a generalized plan.
     *
     * <p>The patch and changed-path lists are defensively copied.</p>
     *
     * @param canonicalRoot next immutable canonical root, or {@code null} when
     *                      unavailable
     * @param root next immutable resolved root
     * @param canonicalPatches exact canonical subtree replacements
     * @param changedPaths metadata paths changed by generalization
     * @param fullSnapshotRebuildAvoidable whether callers can update a prior
     *                                     snapshot from the supplied patches
     * @return generalized plan
     * @throws NullPointerException when {@code root},
     *                              {@code canonicalPatches}, or
     *                              {@code changedPaths} is null
     */
    public static ConformancePlan generalized(FrozenNode canonicalRoot,
                                              FrozenNode root,
                                              List<CanonicalGeneralizationPatch> canonicalPatches,
                                              List<String> changedPaths,
                                              boolean fullSnapshotRebuildAvoidable) {
        return new ConformancePlan(canonicalRoot,
                root,
                true,
                canonicalPatches,
                changedPaths,
                fullSnapshotRebuildAvoidable);
    }

    /**
     * Returns the next canonical root when one was retained.
     *
     * @return immutable canonical root, or {@code null}
     */
    public FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    /**
     * Returns the next resolved root.
     *
     * @return immutable resolved root
     */
    public FrozenNode root() {
        return root;
    }

    /**
     * Materializes the planned resolved root.
     *
     * @return new mutable root independent of this plan
     */
    public Node rootNode() {
        return root.toNode();
    }

    /**
     * Tests whether the plan widened at least one type.
     *
     * @return whether generalization occurred
     */
    public boolean generalized() {
        return generalized;
    }

    /**
     * Returns exact replacements for changed canonical subtrees.
     *
     * @return unmodifiable insertion-ordered patch list
     */
    public List<CanonicalGeneralizationPatch> canonicalPatches() {
        return canonicalPatches;
    }

    /**
     * Returns metadata paths changed by widening.
     *
     * @return unmodifiable insertion-ordered path list
     */
    public List<String> changedPaths() {
        return changedPaths;
    }

    /**
     * Tests whether the supplied canonical patches can avoid rebuilding the
     * complete snapshot.
     *
     * @return whether a full snapshot rebuild is avoidable
     */
    public boolean fullSnapshotRebuildAvoidable() {
        return fullSnapshotRebuildAvoidable;
    }
}
