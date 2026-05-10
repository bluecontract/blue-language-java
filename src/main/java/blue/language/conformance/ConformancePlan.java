package blue.language.conformance;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    public static ConformancePlan unchanged(FrozenNode root) {
        return new ConformancePlan(root, false);
    }

    public static ConformancePlan unchanged(FrozenNode canonicalRoot, FrozenNode root) {
        return new ConformancePlan(canonicalRoot,
                root,
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                canonicalRoot != null);
    }

    public FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    public FrozenNode root() {
        return root;
    }

    public Node rootNode() {
        return root.toNode();
    }

    public boolean generalized() {
        return generalized;
    }

    public List<CanonicalGeneralizationPatch> canonicalPatches() {
        return canonicalPatches;
    }

    public List<String> changedPaths() {
        return changedPaths;
    }

    public boolean fullSnapshotRebuildAvoidable() {
        return fullSnapshotRebuildAvoidable;
    }
}
