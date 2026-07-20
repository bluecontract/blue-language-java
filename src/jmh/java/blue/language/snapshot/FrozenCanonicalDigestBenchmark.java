package blue.language.snapshot;

import blue.language.model.Node;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Compares the frozen-native streaming identity path with the retained generic
 * map/list + Jackson + JCS compatibility oracle over increasingly wide roots.
 */
@State(Scope.Thread)
public class FrozenCanonicalDigestBenchmark {

    @Param({"50", "500", "5000", "25000"})
    public int width;

    private FrozenNode root;

    @Setup(Level.Trial)
    public void setUp() {
        Node document = new Node();
        for (int index = 0; index < width; index++) {
            document.properties("field-" + index,
                    new Node().properties(
                            "status", new Node().value("value-" + index),
                            "sequence", new Node().value(index)));
        }
        root = FrozenNode.fromNode(document);
    }

    @Benchmark
    public String streamingFrozenIdentity() {
        return FrozenCanonicalDigester.calculateBlueId(root);
    }

    @Benchmark
    public String genericJcsOracleIdentity() {
        return FrozenCanonicalDigester.calculateGenericOracle(root);
    }
}
