package blue.language.codec;

import blue.language.model.Node;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Measures portable input parsing, including syntax validation and Node construction. */
@State(Scope.Thread)
public class YamlTokenScreeningBenchmark {
    @Param({"YAML", "JSON"})
    public BlueFormat format;

    @Param({"small", "wide", "longText"})
    public String shape;

    private final StandardBlueCodec codec = new StandardBlueCodec();
    private String source;

    @Setup
    public void setUp() {
        Node node = new Node();
        if ("longText".equals(shape)) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 1_000; i++) {
                text.append("A line of portable source text with punctuation: x,y.z\n");
            }
            node.value(text.toString());
        } else {
            int fields = "wide".equals(shape) ? 1_000 : 5;
            for (int i = 0; i < fields; i++) {
                node.properties("field" + i, new Node().value("text-" + i));
            }
        }
        source = codec.write(node, format);
    }

    @Benchmark
    public Node parseSource() {
        return codec.parseSource(source, format);
    }
}
