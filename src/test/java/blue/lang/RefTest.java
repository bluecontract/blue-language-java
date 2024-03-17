package blue.lang;

import blue.lang.*;
import blue.lang.feature.InlineValueFeature;
import blue.lang.processor.*;
import blue.lang.ref.RefBasedEnricher;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.lang.TestUtils.useNodeNameAsBlueIdProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RefTest {

    @Test
    public void testInlineValueWithFeatureShouldProcessRef() throws Exception {

        Node a = new Node().name("A").value(1);
        Node b = new Node().name("B").type("A").value(2);
        Node c = new Node().name("C").type("B").value(3);

        Node x = new Node().name("X").properties(
                "a", new Node().type("B")
        );

        Node y = new Node().name("Y").type("X").properties(
                "a", new Node().value("should-go-as-ref").features(
                        Collections.singletonList(new InlineValueFeature())
                )
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Arrays.asList(
                        new FeaturesPropagator(),
                        new InlineValueToRefTransformer(),
                        new RefPropagator(refBasedEnricher()),
                        new ValuePropagator(),
                        new TypeAssigner(types)
                )
        );
        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        Merger merger = new Merger(nodeProvider, nodeProcessor, null);
        Node node = new Node();
        merger.merge(node, nodeProvider.fetchByBlueId("Y"));

        assertEquals(node.getProperties().get("a").getValue(), "enriched-should-go-as-ref");
    }

    @Test
    public void testExplicitRefShouldProcessRef() throws Exception {Node a = new Node().name("A").value(1);
        Node b = new Node().name("B").type("A").value(2);
        Node c = new Node().name("C").type("B").value(3);

        Node x = new Node().name("X").properties(
                "a", new Node().type("B")
        );
        Node y = new Node().name("Y").type("X").properties(
                "a", new Node().ref("should-go-as-ref")
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Arrays.asList(
                        new FeaturesPropagator(),
                        new InlineValueToRefTransformer(),
                        new RefPropagator(refBasedEnricher()),
                        new ValuePropagator(),
                        new TypeAssigner(types)
                )
        );

        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        Merger merger = new Merger(nodeProvider, nodeProcessor, null);
        Node node = new Node();
        merger.merge(node, nodeProvider.fetchByBlueId("Y"));

        assertEquals(node.getProperties().get("a").getValue(), "enriched-should-go-as-ref");
    }

    @Test
    public void testInlineValueWithNoFeatureShouldProcessValue() throws Exception {
        Node a = new Node().name("A").value(1);
        Node b = new Node().name("B").type("A").value(2);
        Node c = new Node().name("C").type("B").value(3);

        Node x = new Node().name("X").properties(
                "a", new Node().type("B")
        );
        Node y = new Node().name("Y").type("X").properties(
                "a", new Node().value("should-not-go-as-ref")
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Arrays.asList(
                        new FeaturesPropagator(),
                        new InlineValueToRefTransformer(),
                        new RefPropagator(refBasedEnricher()),
                        new ValuePropagator(),
                        new TypeAssigner(types)
                )
        );

        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        Merger merger = new Merger(nodeProvider, nodeProcessor, null);
        Node node = new Node();
        merger.merge(node, nodeProvider.fetchByBlueId("Y"));

        assertEquals(node.getProperties().get("a").getValue(), "should-not-go-as-ref");
    }

    private RefBasedEnricher refBasedEnricher() {
        return (node, ref) -> {
            node.value("enriched-" + ref);
            node.ref(null);
            return true;
        };
    }

}