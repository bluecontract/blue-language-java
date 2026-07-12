package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.utils.JsonPointer;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

public final class ResolvedSnapshot {

    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final Map<String, FrozenNode> canonicalIndex;
    private final Map<String, FrozenNode> resolvedIndex;
    private final Map<String, Set<ResolvedNodeProvenance>> provenanceByPath;
    private final String blueId;

    public ResolvedSnapshot(Node canonicalRoot, Node resolvedRoot, String blueId) {
        this(FrozenNode.fromNode(canonicalRoot), FrozenNode.fromResolvedNode(resolvedRoot), blueId,
                Collections.<String, Set<ResolvedNodeProvenance>>emptyMap());
    }

    public ResolvedSnapshot(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String blueId) {
        this(canonicalRoot, resolvedRoot, blueId,
                Collections.<String, Set<ResolvedNodeProvenance>>emptyMap());
    }

    public ResolvedSnapshot(FrozenNode canonicalRoot,
                            FrozenNode resolvedRoot,
                            String blueId,
                            Map<String, Set<ResolvedNodeProvenance>> provenanceByPath) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (!this.canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot canonical root must be strict canonical FrozenNode.");
        }
        String expectedBlueId = this.canonicalRoot.blueId();
        if (!expectedBlueId.equals(Objects.requireNonNull(blueId, "blueId"))) {
            throw new IllegalArgumentException("Snapshot blueId must match canonical root blueId.");
        }
        this.canonicalIndex = this.canonicalRoot.pathIndex();
        this.resolvedIndex = this.resolvedRoot.pathIndex();
        this.provenanceByPath = immutableProvenance(provenanceByPath);
        this.blueId = expectedBlueId;
    }

    public Node canonicalRoot() {
        return canonicalRoot.toNode();
    }

    public Node resolvedRoot() {
        return resolvedRoot.toNode();
    }

    public FrozenNode frozenCanonicalRoot() {
        return canonicalRoot;
    }

    public FrozenNode frozenResolvedRoot() {
        return resolvedRoot;
    }

    public FrozenNode canonicalAt(String pointer) {
        return canonicalIndex.get(JsonPointer.canonicalize(pointer));
    }

    public FrozenNode resolvedAt(String pointer) {
        return resolvedIndex.get(JsonPointer.canonicalize(pointer));
    }

    public Node canonicalNodeAt(String pointer) {
        FrozenNode node = canonicalAt(pointer);
        return node != null ? node.toNode() : null;
    }

    public Node resolvedNodeAt(String pointer) {
        FrozenNode node = resolvedAt(pointer);
        return node != null ? node.toNode() : null;
    }

    public Map<String, FrozenNode> canonicalIndex() {
        return canonicalIndex;
    }

    public Map<String, FrozenNode> resolvedIndex() {
        return resolvedIndex;
    }

    public Set<ResolvedNodeProvenance> provenanceAt(String pointer) {
        Set<ResolvedNodeProvenance> provenance = provenanceByPath.get(JsonPointer.canonicalize(pointer));
        return provenance != null ? provenance : Collections.<ResolvedNodeProvenance>emptySet();
    }

    public Map<String, Set<ResolvedNodeProvenance>> provenanceIndex() {
        return provenanceByPath;
    }

    public String blueId() {
        return blueId;
    }

    public CanonicalOverlayPatchEngine canonicalPatchEngine() {
        return new CanonicalOverlayPatchEngine(canonicalRoot);
    }

    public CanonicalPatchResult applyCanonicalPatch(JsonPatch patch) {
        return canonicalPatchEngine().apply(patch);
    }

    private static Map<String, Set<ResolvedNodeProvenance>> immutableProvenance(
            Map<String, Set<ResolvedNodeProvenance>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Set<ResolvedNodeProvenance>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<ResolvedNodeProvenance>> entry : source.entrySet()) {
            String path = JsonPointer.canonicalize(entry.getKey());
            Set<ResolvedNodeProvenance> values = entry.getValue() == null
                    ? Collections.<ResolvedNodeProvenance>emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue()));
            copy.put(path, values);
        }
        return Collections.unmodifiableMap(copy);
    }

}
