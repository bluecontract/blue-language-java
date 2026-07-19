package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.ArrayList;
import java.util.List;

/**
 * Focused allocation and throughput benchmarks for immutable identity paths.
 */
@State(Scope.Thread)
public class FrozenNodeIdentityBenchmark {

    private FrozenNode canonicalWide;
    private FrozenNode resolvedWide;
    private FrozenNode resolvedTailDifference;
    private FrozenNode strictList;
    private List<FrozenNode> strictListItems;
    private String canonicalBlueId;
    private ResolvedSnapshot indexedSnapshot;

    @Setup(Level.Trial)
    public void setUp() {
        Node wide = wideObject(512, false);
        canonicalWide = FrozenNode.fromNode(wide);
        resolvedWide = FrozenNode.fromResolvedNode(wide.clone());
        resolvedTailDifference = FrozenNode.fromResolvedNode(wideObject(512, true));
        canonicalBlueId = canonicalWide.blueId();

        List<Node> items = new ArrayList<>();
        for (int index = 0; index < 512; index++) {
            items.add(index % 8 == 0
                    ? new Node().items(new Node().value("nested-" + index))
                    : new Node().value("value-" + index));
        }
        strictList = FrozenNode.fromNode(new Node().items(items));
        strictListItems = strictList.getItems();
        indexedSnapshot = new ResolvedSnapshot(canonicalWide, resolvedWide, canonicalBlueId);
        indexedSnapshot.resolvedIndex();
    }

    @Benchmark
    public boolean sameResolvedStructureEqual() {
        return canonicalWide.sameResolvedStructure(resolvedWide);
    }

    @Benchmark
    public boolean sameResolvedStructureTailDifference() {
        return resolvedWide.sameResolvedStructure(resolvedTailDifference);
    }

    @Benchmark
    public boolean refreezeNormalizedStructureEqual() {
        FrozenNode left = FrozenNode.fromResolvedNode(canonicalWide.toNode());
        FrozenNode right = FrozenNode.fromResolvedNode(resolvedWide.toNode());
        return left.resolvedStructuralKey().equals(right.resolvedStructuralKey());
    }

    @Benchmark
    public String strictFrozenListIdentity() {
        return FrozenNode.calculateBlueId(strictListItems);
    }

    @Benchmark
    public String rebuiltFrozenListIdentity() {
        List<Object> inputs = new ArrayList<>(strictListItems.size());
        for (int index = 0; index < strictListItems.size(); index++) {
            inputs.add(FrozenNodeToBlueIdInput.getListElement(
                    strictListItems.get(index), index));
        }
        return BlueIdCalculator.INSTANCE.calculate(inputs);
    }

    @Benchmark
    public FrozenNode persistentPureListRebuild() {
        return strictList.withItems(strictListItems);
    }

    @Benchmark
    public ResolvedSnapshot snapshotConstructionWithoutPathIndex() {
        return new ResolvedSnapshot(canonicalWide, resolvedWide, canonicalBlueId);
    }

    @Benchmark
    public FrozenNode snapshotConstructionAndFirstResolvedIndex() {
        ResolvedSnapshot snapshot = new ResolvedSnapshot(
                canonicalWide, resolvedWide, canonicalBlueId);
        return snapshot.resolvedAt("/field511/nested");
    }

    @Benchmark
    public FrozenNode warmResolvedPathLookup() {
        return indexedSnapshot.resolvedAt("/field511/nested");
    }

    private static Node wideObject(int width, boolean changeTail) {
        Node root = new Node();
        for (int index = 0; index < width; index++) {
            String value = changeTail && index == width - 1
                    ? "different"
                    : "value-" + index;
            root.properties("field" + index,
                    new Node().properties("nested", new Node().value(value)));
        }
        return root;
    }
}
