package blue.language.merge;

import blue.language.Blue;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class MergerIntegrationTest {

    private BasicNodeProvider nodeProvider;

    @BeforeEach
    public void setup() {
        nodeProvider = new BasicNodeProvider();
    }

    @Test
    public void shouldBeIdempotentWhenResolvingTheSameNodeTwice() {
        nodeProvider.addSingleDocs(
                "name: Document Anchor\n" +
                "template:\n" +
                "  description: Optional Blue document template.\n"
        );

        nodeProvider.addSingleDocs(
                "name: Document Anchors\n" +
                "type: Dictionary\n" +
                "keyType: Text\n" +
                "valueType:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Document Anchor") + "\n"
        );

        nodeProvider.addSingleDocs(
                "name: My Entry\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Document Anchors") + "\n" +
                "anchor1:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Document Anchor") + "\n" +
                "anchor2:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Document Anchor") + "\n"
        );

        Blue blue = new Blue(nodeProvider);

        Node myEntry = nodeProvider.getNodeByName("My Entry");

        Node resolvedNode = blue.resolve(myEntry);
        Node resolvedNode2 = blue.resolve(resolvedNode);

        assertEquals(blue.nodeToJson(resolvedNode), blue.nodeToJson(resolvedNode2));
    }

    @Test
    public void remainsExtensibleForBinaryCompatibility() {
        assertFalse(Modifier.isFinal(Merger.class.getModifiers()));

        Merger merger = new CompatibleMerger();
        assertNotNull(merger);
    }

    @Test
    public void quotedCanonicalIntegerRefinesThroughANominalIntegerSubtype() {
        nodeProvider.addSingleDocs(
                "name: Order Number\n" +
                "type: Integer");
        String orderNumberBlueId =
                nodeProvider.getBlueIdByName("Order Number");
        Blue blue = new Blue(nodeProvider);
        Node source = blue.yamlToNode(
                "type:\n" +
                "  orderNumber:\n" +
                "    type:\n" +
                "      blueId: " + orderNumberBlueId + "\n" +
                "orderNumber: \"9007199254740992\"");

        Node resolved = blue.resolve(source);
        Node orderNumber =
                resolved.getProperties().get("orderNumber");

        assertEquals(new BigInteger("9007199254740992"),
                orderNumber.getValue());
        assertEquals(orderNumberBlueId,
                orderNumber.getType().getBlueId());
    }

    private static final class CompatibleMerger extends Merger {

        private CompatibleMerger() {
            super(new SequentialMergingProcessor(Collections.emptyList()), blueId -> Collections.emptyList());
        }
    }
}
