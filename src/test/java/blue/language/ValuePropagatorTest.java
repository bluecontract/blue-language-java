package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.ValuePropagator;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.identity.DirectBlueIdCalculator.calculateBlueId;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ValuePropagatorTest {

    @Test
    public void shouldPropagateValue() throws Exception {

        // given
        String a = "name: A\n" +
                "value: xyz";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  value: xyz";

        Map<String, Node> nodes = Stream.of(a, b)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodeProvider nodeProvider = new BasicNodeProvider(nodes.values());
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        // when
        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("B"))).get(0));

        // then
        assertEquals("xyz", node.getValue());
    }

    @Test
    public void shouldRejectConflictingValues() throws Exception {

        // given
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
        BasicNodeProvider nodeProvider = new BasicNodeProvider(nodes.values());
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator()
                )
        );

        // when
        Merger merger = new Merger(mergingProcessor, nodeProvider);

        // then
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("B"))).get(0)));
    }

}
