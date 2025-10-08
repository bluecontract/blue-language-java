package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.AssertDocumentUpdateContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DocumentUpdateChannelTest {

    @Test
    void initializationTriggersDocumentUpdateHandlers() {
        String yaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  documentUpdateChannelX:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /x\n" +
                "  documentUpdateChannelY:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /y\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n" +
                "  setY:\n" +
                "    channel: documentUpdateChannelX\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /y\n" +
                "    propertyValue: 1\n" +
                "  setZ:\n" +
                "    channel: documentUpdateChannelY\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /z\n" +
                "    propertyValue: 1\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        Node xNode = processed.getProperties().get("x");
        assertNotNull(xNode);
        assertEquals(new BigInteger("1"), xNode.getValue());

        Node yNode = processed.getProperties().get("y");
        assertNotNull(yNode);
        assertEquals(new BigInteger("1"), yNode.getValue());

        Node zNode = processed.getProperties().get("z");
        assertNotNull(zNode);
        assertEquals(new BigInteger("1"), zNode.getValue());
    }

    @Test
    void nestedUpdatesPropagateToParentWatchers() {
        String yaml = "name: Nested Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  documentUpdateA:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /a\n" +
                "  setAX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: /a/x\n" +
                "    propertyValue: 1\n" +
                "  setABX:\n" +
                "    channel: lifecycleChannel\n" +
                "    order: 1\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: /a/b/x\n" +
                "    propertyValue: 1\n" +
                "  incrementYOnA:\n" +
                "    channel: documentUpdateA\n" +
                "    type:\n" +
                "      blueId: IncrementProperty\n" +
                "    propertyKey: /y\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        Node a = processed.getProperties().get("a");
        assertNotNull(a);
        Node x = a.getProperties().get("x");
        assertNotNull(x);
        assertEquals(new BigInteger("1"), x.getValue());

        Node b = a.getProperties().get("b");
        assertNotNull(b);
        Node nestedX = b.getProperties().get("x");
        assertNotNull(nestedX);
        assertEquals(new BigInteger("1"), nestedX.getValue());

        Node y = processed.getProperties().get("y");
        assertNotNull(y);
        assertEquals(new BigInteger("2"), y.getValue());
    }

    @Test
    void cascadedUpdatesPropagateThroughEmbeddedScopes() {
        String yaml = "name: Cascading Doc\n" +
                "x:\n" +
                "  name: Embedded X\n" +
                "  y:\n" +
                "    name: Embedded Y\n" +
                "    contracts:\n" +
                "      life:\n" +
                "        type:\n" +
                "          blueId: LifecycleChannel\n" +
                "      setInner:\n" +
                "        channel: life\n" +
                "        event:\n" +
                "          type:\n" +
                "            blueId: DocumentProcessingInitiated\n" +
                "        type:\n" +
                "          blueId: SetProperty\n" +
                "        propertyKey: /a\n" +
                "        propertyValue: 1\n" +
                "  contracts:\n" +
                "    embedded:\n" +
                "      type:\n" +
                "        blueId: ProcessEmbedded\n" +
                "      paths:\n" +
                "        - /y\n" +
                "    documentUpdateFromY:\n" +
                "      type:\n" +
                "        blueId: DocumentUpdateChannel\n" +
                "      path: /y/a\n" +
                "    setFromY:\n" +
                "      channel: documentUpdateFromY\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /a\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  documentUpdateFromChild:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /x/y/a\n" +
                "  setFromChild:\n" +
                "    channel: documentUpdateFromChild\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /a\n" +
                "    propertyValue: 1\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        Node rootA = processed.getProperties().get("a");
        assertNotNull(rootA);
        assertEquals(new BigInteger("1"), rootA.getValue());

        Node x = processed.getProperties().get("x");
        assertNotNull(x);
        Node xA = x.getProperties().get("a");
        assertNotNull(xA);
        assertEquals(new BigInteger("1"), xA.getValue());

        Node y = x.getProperties().get("y");
        assertNotNull(y);
        Node yA = y.getProperties().get("a");
        assertNotNull(yA);
        assertEquals(new BigInteger("1"), yA.getValue());

        assertNull(original.getProperties().get("a"));
        Node originalX = original.getProperties().get("x");
        assertNotNull(originalX);
        assertNull(originalX.getProperties().get("a"));
        Node originalY = originalX.getProperties().get("y");
        assertNotNull(originalY);
        assertNull(originalY.getProperties().get("a"));
    }

    @Test
    void documentUpdateEventExposesRelativePathAndSnapshots() {
        String yaml = "name: Update Doc\n" +
                "a:\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: DocumentProcessingInitiated\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "    watchX:\n" +
                "      type:\n" +
                "        blueId: DocumentUpdateChannel\n" +
                "      path: /x\n" +
                "    assertA:\n" +
                "      channel: watchX\n" +
                "      type:\n" +
                "        blueId: AssertDocumentUpdate\n" +
                "      expectedPath: /x\n" +
                "      expectedOp: add\n" +
                "      expectBeforeNull: true\n" +
                "      expectedAfterValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /a\n" +
                "  watchRoot:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /a/x\n" +
                "  assertRoot:\n" +
                "    channel: watchRoot\n" +
                "    type:\n" +
                "      blueId: AssertDocumentUpdate\n" +
                "    expectedPath: /a/x\n" +
                "    expectedOp: add\n" +
                "    expectBeforeNull: true\n" +
                "    expectedAfterValue: 1\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new AssertDocumentUpdateContractProcessor());

        Node original = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        Node a = processed.getProperties().get("a");
        assertNotNull(a);
        Node x = a.getProperties().get("x");
        assertNotNull(x);
        assertEquals(new BigInteger("1"), x.getValue());
    }
}
