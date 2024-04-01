package blue.language;

import blue.language.feature.InlineValueFeature;
import blue.language.processor.*;
import blue.language.ref.RefBasedEnricher;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.language.TestUtils.useNodeNameAsBlueIdProvider;
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
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new FeaturesPropagator(),
                        new InlineValueToRefTransformer(),
                        new RefPropagator(refBasedEnricher()),
                        new ValuePropagator(),
                        new TypeAssigner()
                )
        );
        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = new Node();
        merger.merge(node, nodeProvider.fetchByBlueId("Y").get(0));

        // TODO: implement referencing
//        assertEquals("enriched-should-go-as-ref", node.getProperties().get("a").getValue());
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
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new FeaturesPropagator(),
                        new InlineValueToRefTransformer(),
                        new RefPropagator(refBasedEnricher()),
                        new ValuePropagator(),
                        new TypeAssigner()
                )
        );

        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = new Node();
        merger.merge(node, nodeProvider.fetchByBlueId("Y").get(0));

        assertEquals("enriched-should-go-as-ref", node.getProperties().get("a").getValue());
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
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new FeaturesPropagator(),
                        new InlineValueToRefTransformer(),
                        new RefPropagator(refBasedEnricher()),
                        new ValuePropagator(),
                        new TypeAssigner()
                )
        );

        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = new Node();
        merger.merge(node, nodeProvider.fetchByBlueId("Y").get(0));

        assertEquals("should-not-go-as-ref", node.getProperties().get("a").getValue());
    }

    private RefBasedEnricher refBasedEnricher() {
        return (node, ref) -> {
            node.value("enriched-" + ref);
            node.ref(null);
            return true;
        };
    }

}