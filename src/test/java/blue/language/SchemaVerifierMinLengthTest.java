package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.processor.*;
import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.TestUtils.indent;
import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.identity.DirectBlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SchemaVerifierMinLengthTest {

    private Node node;
    private Schema schema;
    private BasicNodeProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        schema = new Schema();
        node = new Node()
                .value("xyz")
                .schema(schema);
        mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new SchemaPropagator(),
                        new SchemaVerifier()
                )
        );
        merger = new Merger(mergingProcessor, e -> null);
    }

    @Test
    public void shouldAcceptValueMeetingMinimumLength() throws Exception {
        // given
        schema.minLength(3);

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectValueBelowMinimumLength() throws Exception {
        // given
        schema.minLength(4);

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptValueMeetingInheritedMinimumLength() throws Exception {

        // given
        String a = "name: A\n" +
                   "schema:\n" +
                   "  minLength: 3";

        String b = "name: B\n" +
                   "type:\n" +
                   "  name: A\n" +
                   "  schema:\n" +
                   "    minLength: 3\n" +
                   "schema:\n" +
                   "  minLength: 4";

        String c = "name: C\n" +
                   "type:\n" +
                   "  name: B\n" +
                   "  type:\n" +
                   "    name: A\n" +
                   "    schema:\n" +
                   "      minLength: 3\n" +
                   "  schema:\n" +
                   "    minLength: 4\n" +
                   "value: Abcd";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodeProvider nodeProvider = new BasicNodeProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        // when
        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0));
        // then
        assertEquals("Abcd", node.getValue());

    }

    @Test
    public void shouldRejectValueBelowStrongestInheritedMinimumLength() throws Exception {

        // given
        String a = "name: A\n" +
                   "schema:\n" +
                   "  minLength: 3";

        String b = "name: B\n" +
                   "type:\n" +
                   "  name: A\n" +
                   "  schema:\n" +
                   "    minLength: 3\n" +
                   "schema:\n" +
                   "  minLength: 4";

        String c = "name: C\n" +
                   "type:\n" +
                   "  name: B\n" +
                   "  type:\n" +
                   "    name: A\n" +
                   "    schema:\n" +
                   "      minLength: 3\n" +
                   "  schema:\n" +
                   "    minLength: 4\n" +
                   "value: Abc";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodeProvider nodeProvider = new BasicNodeProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        // when
        Throwable failure = captureFailure(
                () -> merger.resolve(nodeProvider.fetchByBlueId(
                        calculateBlueId(nodes.get("C"))).get(0)));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);

    }

    @Test
    public void shouldApplyStricterNestedMinLengthOverride() throws Exception {

        // given
        String a = "name: A\n" +
                   "type: Text\n" +
                   "schema:\n" +
                   "  minLength: 3";

        String b = "name: B\n" +
                   "type:\n" +
                   indent(a, 2) + "\n" +
                   "schema:\n" +
                   "  minLength: 4";

        String x = "name: X\n" +
                   "a:\n" +
                   "  type:\n" +
                   indent(b, 4) + "\n" +
                   "  schema:\n" +
                   "    minLength: 5";

        String y = "name: Y\n" +
                   "type:\n" +
                   indent(x, 2) + "\n" +
                   "a:\n" +
                   "  value: Abcde";

        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(a, b, x, y);
        merger = new Merger(mergingProcessor, e -> null);

        // when
        Node node = merger.resolve(nodeProvider.getNodeByName("Y"));
        // then
        assertEquals("Abcde", node.getProperties().get("a").getValue());

    }

    @Test
    public void shouldRetainStricterInheritedNestedMinLength() throws Exception {

        // given
        String a = "name: A\n" +
                   "type: Text\n" +
                   "schema:\n" +
                   "  minLength: 3";

        String b = "name: B\n" +
                   "type:\n" +
                   indent(a, 2) + "\n" +
                   "schema:\n" +
                   "  minLength: 4";

        String x = "name: X\n" +
                   "a:\n" +
                   "  type:\n" +
                   indent(b, 4) + "\n" +
                   "  schema:\n" +
                   "    minLength: 2";

        String y = "name: Y\n" +
                   "type:\n" +
                   indent(x, 2) + "\n" +
                   "a:\n" +
                   "  value: Abcd";

        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(a, b, x, y);
        merger = new Merger(mergingProcessor, e -> null);

        // when
        Node node = merger.resolve(nodeProvider.getNodeByName("Y"));
        // then
        assertEquals("Abcd", node.getProperties().get("a").getValue());

    }


    @Test
    public void shouldRejectNestedValueBelowInheritedMinimumLength() throws Exception {

        // given
        String a = "name: A\n" +
                   "schema:\n" +
                   "  minLength: 3";

        String b = "name: B\n" +
                   "type:\n" +
                   indent(a, 2) + "\n" +
                   "schema:\n" +
                   "  minLength: 4";

        String x = "name: X\n" +
                   "a:\n" +
                   "  type:\n" +
                   indent(b, 4) + "\n" +
                   "  schema:\n" +
                   "    minLength: 2";

        String y = "name: Y\n" +
                   "type:\n" +
                   indent(x, 2) + "\n" +
                   "a:\n" +
                   "  value: Abc";

        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(a, b, x, y);
        merger = new Merger(mergingProcessor, e -> null);

        // when
        Throwable failure = captureFailure(
                () -> merger.resolve(nodeProvider.getNodeByName("Y")));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);

    }
}
