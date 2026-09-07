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
import blue.language.merge.processor.*;
import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SchemaVerifierMinLengthTest {

    private Node node;
    private Schema schema;
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
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: A\n" +
                "schema:\n" +
                "  minLength: 3");
        nodeProvider.addSingleDocs(
                "name: B\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                "schema:\n" +
                "  minLength: 4");
        String c = "name: C\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                   "value: Abcd";
        nodeProvider.addSingleDocs(c);
        merger = new Merger(mergingProcessor, nodeProvider);

        // when
        Node node = merger.resolve(nodeProvider.getNodeByName("C"));
        // then
        assertEquals("Abcd", node.getValue());

    }

    @Test
    public void shouldRejectValueBelowStrongestInheritedMinimumLength() throws Exception {

        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: A\n" +
                "schema:\n" +
                "  minLength: 3");
        nodeProvider.addSingleDocs(
                "name: B\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                "schema:\n" +
                "  minLength: 4");
        String c = "name: C\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                   "value: Abc";
        nodeProvider.addSingleDocs(c);
        merger = new Merger(mergingProcessor, nodeProvider);

        // when
        Throwable failure = captureFailure(
                () -> merger.resolve(nodeProvider.getNodeByName("C")));

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

        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(a);
        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "schema:\n" +
                   "  minLength: 4";
        nodeProvider.addSingleDocs(b);

        String x = "name: X\n" +
                   "a:\n" +
                   "  type:\n" +
                   "    blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                   "  schema:\n" +
                   "    minLength: 5";
        nodeProvider.addSingleDocs(x);

        String y = "name: Y\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("X") + "\n" +
                   "a:\n" +
                   "  value: Abcde";
        nodeProvider.addSingleDocs(y);
        merger = new Merger(mergingProcessor, nodeProvider);

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

        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(a);
        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "schema:\n" +
                   "  minLength: 4";
        nodeProvider.addSingleDocs(b);

        String x = "name: X\n" +
                   "a:\n" +
                   "  type:\n" +
                   "    blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                   "  schema:\n" +
                   "    minLength: 2";
        nodeProvider.addSingleDocs(x);

        String y = "name: Y\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("X") + "\n" +
                   "a:\n" +
                   "  value: Abcd";
        nodeProvider.addSingleDocs(y);
        merger = new Merger(mergingProcessor, nodeProvider);

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

        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(a);
        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "schema:\n" +
                   "  minLength: 4";
        nodeProvider.addSingleDocs(b);

        String x = "name: X\n" +
                   "a:\n" +
                   "  type:\n" +
                   "    blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                   "  schema:\n" +
                   "    minLength: 2";
        nodeProvider.addSingleDocs(x);

        String y = "name: Y\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("X") + "\n" +
                   "a:\n" +
                   "  value: Abc";
        nodeProvider.addSingleDocs(y);
        merger = new Merger(mergingProcessor, nodeProvider);

        // when
        Throwable failure = captureFailure(
                () -> merger.resolve(nodeProvider.getNodeByName("Y")));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);

    }
}
