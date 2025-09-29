package blue.language.merge;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}


