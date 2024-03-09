package blue.lang;

import blue.lang.graph.*;
import blue.lang.graph.feature.InlineValueFeature;
import blue.lang.graph.processor.*;
import blue.lang.graph.ref.RefBasedEnricher;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RefTest {

    @Test
    public void testInlineValueWithFeatureShouldProcessRef() throws Exception {

        BasicNode a = new BasicNode().name("A").value(1);
        BasicNode b = new BasicNode().name("B").type("A").value(2);
        BasicNode c = new BasicNode().name("C").type("B").value(3);

        BasicNode x = new BasicNode().name("X").properties(
                "a", new BasicNode().type("B")
        );

        BasicNode y = new BasicNode().name("Y").type("X").properties(
                "a", new BasicNode().value("should-go-as-ref").features(
                        Collections.singletonList(new InlineValueFeature())
                )
        );

        List<BasicNode> nodes = Arrays.asList(a, b, c, x, y);
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
        BasicNodeManager manager = new BasicNodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        BasicNode node = new BasicNode();
        merger.merge(node, manager.getNode("Y"));

        assertEquals(node.getProperties().get("a").getValue(), "enriched-should-go-as-ref");
    }

    @Test
    public void testExplicitRefShouldProcessRef() throws Exception {BasicNode a = new BasicNode().name("A").value(1);
        BasicNode b = new BasicNode().name("B").type("A").value(2);
        BasicNode c = new BasicNode().name("C").type("B").value(3);

        BasicNode x = new BasicNode().name("X").properties(
                "a", new BasicNode().type("B")
        );
        BasicNode y = new BasicNode().name("Y").type("X").properties(
                "a", new BasicNode().ref("should-go-as-ref")
        );

        List<BasicNode> nodes = Arrays.asList(a, b, c, x, y);
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
        BasicNodeManager manager = new BasicNodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        BasicNode node = new BasicNode();
        merger.merge(node, manager.getNode("Y"));

        assertEquals(node.getProperties().get("a").getValue(), "enriched-should-go-as-ref");
    }

    @Test
    public void testInlineValueWithNoFeatureShouldProcessValue() throws Exception {
        BasicNode a = new BasicNode().name("A").value(1);
        BasicNode b = new BasicNode().name("B").type("A").value(2);
        BasicNode c = new BasicNode().name("C").type("B").value(3);

        BasicNode x = new BasicNode().name("X").properties(
                "a", new BasicNode().type("B")
        );
        BasicNode y = new BasicNode().name("Y").type("X").properties(
                "a", new BasicNode().value("should-not-go-as-ref")
        );

        List<BasicNode> nodes = Arrays.asList(a, b, c, x, y);
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
        BasicNodeManager manager = new BasicNodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        BasicNode node = new BasicNode();
        merger.merge(node, manager.getNode("Y"));

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