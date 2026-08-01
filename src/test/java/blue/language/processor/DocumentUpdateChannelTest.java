package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.AssertDocumentUpdateContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DocumentUpdateChannelTest {

    @Test
    void shouldRenderOneUnderlyingDocumentUpdateRelativeToEveryReceivingScope() {
        // given
        DocumentProcessingRuntime.DocumentUpdateData update =
                new DocumentProcessingRuntime.DocumentUpdateData(
                        "/a/b/x",
                        null,
                        new Node().value(BigInteger.ONE),
                        JsonPatch.Op.ADD,
                        "/a/b",
                        Collections.<String>emptyList());

        // when
        Node sourceEvent =
                ProcessorEngine.createDocumentUpdateEvent(
                        update, "/a/b");
        Node ancestorEvent =
                ProcessorEngine.createDocumentUpdateEvent(
                        update, "/a");
        Node rootEvent =
                ProcessorEngine.createDocumentUpdateEvent(
                        update, "/");

        // then
        assertEquals("/a/b/x", update.path());
        assertEquals("add", sourceEvent.getAsText("/op"));
        assertEquals("add", ancestorEvent.getAsText("/op"));
        assertEquals("add", rootEvent.getAsText("/op"));
        assertEquals("/x",
                sourceEvent.getAsText("/path"));
        assertEquals("/",
                sourceEvent.getAsText(
                        "/sourceScopePath"));

        assertEquals("/b/x",
                ancestorEvent.getAsText("/path"));
        assertEquals("/b",
                ancestorEvent.getAsText(
                        "/sourceScopePath"));

        assertEquals("/a/b/x",
                rootEvent.getAsText("/path"));
        assertEquals("/a/b",
                rootEvent.getAsText(
                        "/sourceScopePath"));
    }

    @Test
    void shouldVerifyInitializationTriggersDocumentUpdateHandlers() {
        // given
        String yaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "  documentUpdateChannelX:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /x\n" +
                "  documentUpdateChannelY:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /y\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n" +
                "  setY:\n" +
                "    channel: documentUpdateChannelX\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /y\n" +
                "    propertyValue: 1\n" +
                "  setZ:\n" +
                "    channel: documentUpdateChannelY\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /z\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();
        Node xNode = processed.getProperties().get("x");
        Node yNode = processed.getProperties().get("y");
        Node zNode = processed.getProperties().get("z");

        // then
        assertNotNull(xNode);
        assertEquals(new BigInteger("1"), xNode.getValue());
        assertNotNull(yNode);
        assertEquals(new BigInteger("1"), yNode.getValue());
        assertNotNull(zNode);
        assertEquals(new BigInteger("1"), zNode.getValue());
    }

    @Test
    void shouldVerifyNestedUpdatesPropagateToParentWatchers() {
        // given
        String yaml = "name: Nested Doc\n" +
                "a:\n" +
                "  b:\n" +
                "    existing: true\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "  documentUpdateA:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /a\n" +
                "  setAX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "    propertyKey: /a/x\n" +
                "    propertyValue: 1\n" +
                "  setABX:\n" +
                "    channel: lifecycleChannel\n" +
                "    order: 1\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "    propertyKey: /a/b/x\n" +
                "    propertyValue: 1\n" +
                "  incrementYOnA:\n" +
                "    channel: documentUpdateA\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "    propertyKey: /y\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();
        Node a = processed.getProperties().get("a");
        Node x = a.getProperties().get("x");
        Node b = a.getProperties().get("b");
        Node nestedX = b.getProperties().get("x");
        Node y = processed.getProperties().get("y");

        // then
        assertNotNull(a);
        assertNotNull(x);
        assertEquals(new BigInteger("1"), x.getValue());
        assertNotNull(b);
        assertNotNull(nestedX);
        assertEquals(new BigInteger("1"), nestedX.getValue());
        assertNotNull(y);
        assertEquals(new BigInteger("2"), y.getValue());
    }

    @Test
    void shouldVerifyCascadedUpdatesPropagateThroughEmbeddedScopes() {
        // given
        String yaml = "name: Cascading Doc\n" +
                "x:\n" +
                "  name: Embedded X\n" +
                "  y:\n" +
                "    name: Embedded Y\n" +
                "    contracts:\n" +
                "      life:\n" +
                "        type:\n" +
                "          blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "      setInner:\n" +
                "        channel: life\n" +
                "        event:\n" +
                "          type:\n" +
                "            blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "        type:\n" +
                "          blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "        propertyKey: /a\n" +
                "        propertyValue: 1\n" +
                "  contracts:\n" +
                "    embedded:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "      paths:\n" +
                "        - /y\n" +
                "    documentUpdateFromY:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "      path: /y/a\n" +
                "    setFromY:\n" +
                "      channel: documentUpdateFromY\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      propertyKey: /a\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  documentUpdateFromChild:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /x/y/a\n" +
                "  setFromChild:\n" +
                "    channel: documentUpdateFromChild\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /a\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();
        Node rootA = processed.getProperties().get("a");
        Node x = processed.getProperties().get("x");
        Node xA = x.getProperties().get("a");
        Node y = x.getProperties().get("y");
        Node yA = y.getProperties().get("a");
        Node originalX = original.getProperties().get("x");
        Node originalY = originalX.getProperties().get("y");

        // then
        assertNotNull(rootA, result.status() + ": " + diagnosticMessage(result)
                + "\n" + blue.nodeToYaml(processed));
        assertEquals(new BigInteger("1"), rootA.getValue());
        assertNotNull(x);
        assertNotNull(xA);
        assertEquals(new BigInteger("1"), xA.getValue());
        assertNotNull(y);
        assertNotNull(yA);
        assertEquals(new BigInteger("1"), yA.getValue());

        assertNull(original.getProperties().get("a"));
        assertNotNull(originalX);
        assertNull(originalX.getProperties().get("a"));
        assertNotNull(originalY);
        assertNull(originalY.getProperties() != null ? originalY.getProperties().get("a") : null);
    }

    @Test
    void shouldVerifyDocumentUpdateEventExposesRelativePathAndSnapshots() {
        // given
        String yaml = "name: Update Doc\n" +
                "a:\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "    watchX:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "      path: /x\n" +
                "    assertA:\n" +
                "      channel: watchX\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.ASSERT_DOCUMENT_UPDATE + "\n" +
                "      expectedPath: /x\n" +
                "      expectedOp: add\n" +
                "      expectBeforeNull: true\n" +
                "      expectedAfterValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /a\n" +
                "  watchRoot:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /a/x\n" +
                "  assertRoot:\n" +
                "    channel: watchRoot\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.ASSERT_DOCUMENT_UPDATE + "\n" +
                "    expectedPath: /a/x\n" +
                "    expectedOp: add\n" +
                "    expectBeforeNull: true\n" +
                "    expectedAfterValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new AssertDocumentUpdateContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();
        Node a = processed.getProperties().get("a");
        Node x = a.getProperties().get("x");

        // then
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(), diagnosticMessage(result));
        assertNotNull(a);
        assertNotNull(x);
        assertEquals(new BigInteger("1"), x.getValue());
    }
}
