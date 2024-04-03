package blue.language;

import blue.language.model.Node;
import blue.language.processor.SequentialMergingProcessor;
import blue.language.processor.ValuePropagator;
import blue.language.utils.BasicNodesProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ValuePropagatorTest {

    @Test
    public void testValueShouldPropagate() throws Exception {

        String a = "name: A\n" +
                "value: xyz";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  value: xyz";

        Map<String, Node> nodes = Stream.of(a, b)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("B"))).get(0));

        assertEquals("xyz", node.getValue());
    }

    @Test
    public void testValuesMustNotConflict() throws Exception {

        String a = "name: A\n" +
                "value: xyz";

        String b = "name: B\n" +
                "value: abc\n" +
                "type:\n" +
                "  name: A\n" +
                "  value: xyz";

        Map<String, Node> nodes = Stream.of(a, b)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("B"))).get(0)));
    }

}