package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorBatchPatchTest {

    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void processorExecutionContextApplyPatchesWorksInsideHandler() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new ApplyBatchPatchContractProcessor());
        Node original = blue.yamlToNode(
                "name: Batch Handler Doc\n" +
                "contracts:\n" +
                "  lifecycle:\n" +
                "    type:\n" +
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  apply:\n" +
                "    channel: lifecycle\n" +
                "    type:\n" +
                "      blueId: AjWAjR4NcDYJHMhkAkX9DZKqGbHs8vkCRpjXiHRkLPMw\n");

        DocumentProcessingResult result = blue.initializeDocument(original);

        assertEquals("one", result.document().getAsText("/a"));
        assertEquals("two", result.document().getAsText("/b"));
    }

    @Test
    void boundaryViolationInSecondPatchRollsBackWholeInvocation() {
        Node document = new Node();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        ContractBundle bundle = ContractBundle.builder().build();

        assertThrows(RunTerminationException.class,
                () -> execution.handlePatches(
                        "/foo", bundle, Arrays.asList(
                                JsonPatch.add(
                                        "/foo/a",
                                        new Node().value(
                                                "tentative-first")),
                                JsonPatch.add(
                                        "/bar",
                                        new Node().value(
                                                "outside")),
                                JsonPatch.add(
                                        "/foo/c",
                                        new Node().value(
                                                "tentative-third"))
                        ), false));

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertNull(result.document().getProperties());
        assertTrue(execution.runtime().isRunTerminated());
        assertFalse(execution.runtime()
                .isScopeTerminated("/foo"));
    }

    @Test
    void reservedKeyViolationInSecondPatchRollsBackWholeInvocation() {
        Node document = new Node().properties("foo", new Node());
        String exactInput = document.toString();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        ContractBundle bundle = ContractBundle.builder().build();

        assertThrows(RunTerminationException.class,
                () -> execution.handlePatches(
                        "/foo", bundle, Arrays.asList(
                                JsonPatch.add(
                                        "/foo/a",
                                        new Node().value(
                                                "tentative-first")),
                                JsonPatch.add(
                                        "/foo/contracts/initialized",
                                        new Node().value(
                                                "reserved"))
                        ), false));

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(exactInput,
                result.document().toString());
        assertFalse(hasProperty(
                result.document().getAsNode("/foo"), "a"));
        assertNull(result.document().getAsNode("/foo")
                .getContracts());
        assertTrue(execution.runtime().isRunTerminated());
    }

    @Test
    void invalidSecondPatchRollsBackAllTentativePatches() {
        Node document = new Node().properties("foo", new Node());
        String exactInput = document.toString();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        ContractBundle bundle = ContractBundle.builder().build();

        assertThrows(RunTerminationException.class,
                () -> execution.handlePatches(
                        "/foo", bundle, Arrays.asList(
                                JsonPatch.add(
                                        "/foo/a",
                                        new Node().value(
                                                "tentative-first")),
                                JsonPatch.remove(
                                        "/foo/missing"),
                                JsonPatch.add(
                                        "/foo/c",
                                        new Node().value(
                                                "tentative-third"))
                        ), false));

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(exactInput,
                result.document().toString());
        Node foo = result.document().getAsNode("/foo");
        assertFalse(hasProperty(foo, "a"));
        assertFalse(hasProperty(foo, "c"));
        assertTrue(execution.runtime().isRunTerminated());
    }

    @Test
    void cyclicMemberTraversalInLaterPatchRollsBackWholeInvocation() {
        Node document = new Node().properties(
                "foo",
                new Node().properties(
                        "cyclic",
                        new Node().blueId(CYCLIC_MEMBER_BLUE_ID)));
        String exactInput = document.toString();
        ProcessorEngine.Execution execution =
                new ProcessorEngine.Execution(new DocumentProcessor(), document);

        assertThrows(RunTerminationException.class,
                () -> execution.handlePatches(
                        "/foo",
                        ContractBundle.builder().build(),
                        Arrays.asList(
                                JsonPatch.add(
                                        "/foo/tentative",
                                        new Node().value("must roll back")),
                                JsonPatch.add(
                                        "/foo/cyclic/member",
                                        new Node().value("forbidden"))),
                        false));

        DocumentProcessingResult result = execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                diagnosticCategory(result));
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(exactInput, result.document().toString());
        assertFalse(hasProperty(result.document().getAsNode("/foo"), "tentative"));
    }

    @Test
    void documentUpdateChannelsReceiveBatchUpdatesInPatchOrder() {
        RecordDocumentUpdateContractProcessor recorder = new RecordDocumentUpdateContractProcessor();
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new ApplyBatchPatchContractProcessor());
        blue.registerContractProcessor(recorder);
        Node original = blue.yamlToNode(
                "name: Batch Update Doc\n" +
                "contracts:\n" +
                "  lifecycle:\n" +
                "    type:\n" +
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  watchA:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /a\n" +
                "  watchB:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /b\n" +
                "  apply:\n" +
                "    channel: lifecycle\n" +
                "    type:\n" +
                "      blueId: AjWAjR4NcDYJHMhkAkX9DZKqGbHs8vkCRpjXiHRkLPMw\n" +
                "  recordA:\n" +
                "    channel: watchA\n" +
                "    type:\n" +
                "      blueId: qLb75fi7BHJf8HvxXNTJP8Zo2fCsA3t6Lz5R269qUiC\n" +
                "  recordB:\n" +
                "    channel: watchB\n" +
                "    type:\n" +
                "      blueId: qLb75fi7BHJf8HvxXNTJP8Zo2fCsA3t6Lz5R269qUiC\n");

        blue.initializeDocument(original);

        assertEquals(Arrays.asList("/a", "/b"), recorder.paths());
    }

    @Test
    void unmatchedDocumentUpdateChannelDoesNotMaterializeUpdateNodes() {
        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(
                "name: Lazy Update Doc\n" +
                "contracts:\n" +
                "  watchOther:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /other\n");
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        execution.preflightScope("/");

        execution.handlePatches("/", execution.bundleForScope("/"), Collections.singletonList(
                JsonPatch.add("/a", new Node().value("one"))
        ), false);

        assertEquals(0, execution.runtime().documentUpdateBeforeNodeMaterializationsForTest());
        assertEquals(0, execution.runtime().documentUpdateAfterNodeMaterializationsForTest());
    }

    @Test
    void matchingDocumentUpdateChannelMaterializesUpdateNodes() {
        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(
                "name: Lazy Update Doc\n" +
                "a: old\n" +
                "contracts:\n" +
                "  watchA:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /a\n");
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(new DocumentProcessor(), document);
        execution.preflightScope("/");

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
