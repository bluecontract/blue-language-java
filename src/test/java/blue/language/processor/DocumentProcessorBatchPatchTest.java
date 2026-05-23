package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.ApplyBatchPatchContractProcessor;
import blue.language.processor.contracts.RecordDocumentUpdateContractProcessor;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorBatchPatchTest {

    @Test
    void processorExecutionContextApplyPatchesWorksInsideHandler() {
        Blue blue = new Blue();
        blue.registerContractProcessor(new ApplyBatchPatchContractProcessor());
        Node original = blue.yamlToNode(
                "name: Batch Handler Doc\n" +
                "contracts:\n" +
                "  lifecycle:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  apply:\n" +
                "    channel: lifecycle\n" +
                "    type:\n" +
                "      blueId: ApplyBatchPatch\n");

        DocumentProcessingResult result = blue.initializeDocument(original);

        assertEquals("one", result.document().getAsText("/a"));
        assertEquals("two", result.document().getAsText("/b"));
    }

    @Test
    void boundaryViolationInSecondPatchRollsBackEarlierPatch() {
        Node document = new Node();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatches("/foo", bundle, Arrays.asList(
                JsonPatch.add("/foo/a", new Node().value("applied-first")),
                JsonPatch.add("/bar", new Node().value("outside"))
        ), false);

        Node resultDoc = execution.result().document();
        Node foo = resultDoc.getAsNode("/foo");
        assertFalse(hasProperty(foo, "a"));
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
    }

    @Test
    void reservedKeyViolationInSecondPatchRollsBackEarlierPatch() {
        Node document = new Node().properties("foo", new Node());
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatches("/foo", bundle, Arrays.asList(
                JsonPatch.add("/foo/a", new Node().value("applied-first")),
                JsonPatch.add("/foo/contracts/initialized", new Node().value("reserved"))
        ), false);

        Node resultDoc = execution.result().document();
        Node foo = resultDoc.getAsNode("/foo");
        assertFalse(hasProperty(foo, "a"));
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
    }

    @Test
    void documentUpdateChannelsReceiveBatchUpdatesInPatchOrder() {
        RecordDocumentUpdateContractProcessor recorder = new RecordDocumentUpdateContractProcessor();
        Blue blue = new Blue();
        blue.registerContractProcessor(new ApplyBatchPatchContractProcessor());
        blue.registerContractProcessor(recorder);
        Node original = blue.yamlToNode(
                "name: Batch Update Doc\n" +
                "contracts:\n" +
                "  lifecycle:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  watchA:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /a\n" +
                "  watchB:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /b\n" +
                "  apply:\n" +
                "    channel: lifecycle\n" +
                "    type:\n" +
                "      blueId: ApplyBatchPatch\n" +
                "  recordA:\n" +
                "    channel: watchA\n" +
                "    type:\n" +
                "      blueId: RecordDocumentUpdate\n" +
                "  recordB:\n" +
                "    channel: watchB\n" +
                "    type:\n" +
                "      blueId: RecordDocumentUpdate\n");

        blue.initializeDocument(original);

        assertEquals(Arrays.asList("/a", "/b"), recorder.paths());
    }

    @Test
    void unmatchedDocumentUpdateChannelDoesNotMaterializeUpdateNodes() {
        Blue blue = new Blue();
        Node document = blue.yamlToNode(
                "name: Lazy Update Doc\n" +
                "contracts:\n" +
                "  watchOther:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /other\n");
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        execution.loadBundles("/");

        execution.handlePatches("/", execution.bundleForScope("/"), Collections.singletonList(
                JsonPatch.add("/a", new Node().value("one"))
        ), false);

        assertEquals(0, execution.runtime().documentUpdateBeforeNodeMaterializationsForTest());
        assertEquals(0, execution.runtime().documentUpdateAfterNodeMaterializationsForTest());
    }

    @Test
    void matchingDocumentUpdateChannelMaterializesUpdateNodes() {
        Blue blue = new Blue();
        Node document = blue.yamlToNode(
                "name: Lazy Update Doc\n" +
                "a: old\n" +
                "contracts:\n" +
                "  watchA:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /a\n");
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        execution.loadBundles("/");

        execution.handlePatches("/", execution.bundleForScope("/"), Collections.singletonList(
                JsonPatch.replace("/a", new Node().value("new"))
        ), false);

        assertEquals(1, execution.runtime().documentUpdateBeforeNodeMaterializationsForTest());
        assertEquals(1, execution.runtime().documentUpdateAfterNodeMaterializationsForTest());
    }

    private boolean hasProperty(Node node, String key) {
        assertNotNull(node);
        Map<String, Node> properties = node.getProperties();
        return properties != null && properties.containsKey(key);
    }
}
