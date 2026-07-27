package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.AssertDocumentUpdateContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DocumentUpdateChannelTest {

    @Test
    void documentUpdatePathsAreRelativeToEveryReceivingScope() {
        DocumentProcessingRuntime.DocumentUpdateData update =
                new DocumentProcessingRuntime.DocumentUpdateData(
                        "/a/b/x",
                        null,
                        new Node().value(BigInteger.ONE),
                        JsonPatch.Op.ADD,
                        "/a/b",
                        Collections.<String>emptyList());

        Node sourceEvent =
                ProcessorEngine.createDocumentUpdateEvent(
                        update, "/a/b");
        assertEquals("/x",
                sourceEvent.getAsText("/path"));
        assertEquals("/",
                sourceEvent.getAsText(
                        "/sourceScopePath"));

        Node ancestorEvent =
                ProcessorEngine.createDocumentUpdateEvent(
                        update, "/a");
        assertEquals("/b/x",
                ancestorEvent.getAsText("/path"));
        assertEquals("/b",
                ancestorEvent.getAsText(
                        "/sourceScopePath"));

        Node rootEvent =
                ProcessorEngine.createDocumentUpdateEvent(
                        update, "/");
        assertEquals("/a/b/x",
                rootEvent.getAsText("/path"));
        assertEquals("/a/b",
                rootEvent.getAsText(
                        "/sourceScopePath"));
    }

    @Test
    void initializationTriggersDocumentUpdateHandlers() {
        String yaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  documentUpdateChannelX:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /x\n" +
                "  documentUpdateChannelY:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /y\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n" +
                "  setY:\n" +
                "    channel: documentUpdateChannelX\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /y\n" +
                "    propertyValue: 1\n" +
                "  setZ:\n" +
                "    channel: documentUpdateChannelY\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /z\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
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
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  documentUpdateA:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /a\n" +
                "  setAX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    propertyKey: /a/x\n" +
                "    propertyValue: 1\n" +
                "  setABX:\n" +
                "    channel: lifecycleChannel\n" +
                "    order: 1\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    propertyKey: /a/b/x\n" +
                "    propertyValue: 1\n" +
                "  incrementYOnA:\n" +
                "    channel: documentUpdateA\n" +
                "    type:\n" +
                "      blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "    propertyKey: /y\n";

        Blue blue = ProcessorTestSupport.blue();
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
                "          blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "      setInner:\n" +
                "        channel: life\n" +
                "        event:\n" +
                "          type:\n" +
                "            blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "        type:\n" +
                "          blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "        propertyKey: /a\n" +
                "        propertyValue: 1\n" +
                "  contracts:\n" +
                "    embedded:\n" +
                "      type:\n" +
                "        blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "      paths:\n" +
                "        - /y\n" +
                "    documentUpdateFromY:\n" +
                "      type:\n" +
                "        blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "      path: /y/a\n" +
                "    setFromY:\n" +
                "      channel: documentUpdateFromY\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /a\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  documentUpdateFromChild:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /x/y/a\n" +
                "  setFromChild:\n" +
                "    channel: documentUpdateFromChild\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /a\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        Node rootA = processed.getProperties().get("a");
        assertNotNull(rootA, result.status() + ": " + diagnosticMessage(result)
                + "\n" + blue.nodeToYaml(processed));
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
        assertNull(originalY.getProperties() != null ? originalY.getProperties().get("a") : null);
    }

    @Test
    void documentUpdateEventExposesRelativePathAndSnapshots() {
        String yaml = "name: Update Doc\n" +
                "a:\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "    watchX:\n" +
                "      type:\n" +
                "        blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "      path: /x\n" +
                "    assertA:\n" +
                "      channel: watchX\n" +
                "      type:\n" +
                "        blueId: 2QCfZuct9TQRCmgE4q6PneDoZFcshqMLYpsNGpxvfwMd\n" +
                "      expectedPath: /x\n" +
                "      expectedOp: add\n" +
                "      expectBeforeNull: true\n" +
                "      expectedAfterValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /a\n" +
                "  watchRoot:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /a/x\n" +
                "  assertRoot:\n" +
                "    channel: watchRoot\n" +
                "    type:\n" +
                "      blueId: 2QCfZuct9TQRCmgE4q6PneDoZFcshqMLYpsNGpxvfwMd\n" +
                "    expectedPath: /a/x\n" +
                "    expectedOp: add\n" +
                "    expectBeforeNull: true\n" +
                "    expectedAfterValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new AssertDocumentUpdateContractProcessor());

        Node original = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(original);
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(), diagnosticMessage(result));
        Node processed = result.document();

        Node a = processed.getProperties().get("a");
        assertNotNull(a);
        Node x = a.getProperties().get("x");
        assertNotNull(x);
        assertEquals(new BigInteger("1"), x.getValue());
    }
}
