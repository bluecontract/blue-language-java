package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.RecordDocumentUpdateContractProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current-delivery versus later-work proofs for mutable contract surfaces. */
final class DocumentProcessorContractSurfaceEvolutionTest {

    @Test
    void shouldExcludeAddedChannelFromCreatingUpdateAndUseItLater() {
        // given
        RecordDocumentUpdateContractProcessor recorder =
                new RecordDocumentUpdateContractProcessor();
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(recorder);
        Node document = blue.yamlToNode(
                "name: Dynamic contracts\n" +
                "contracts: {}\n");
        ProcessorInvocationState execution = new ProcessorInvocationState(
                blue.getDocumentProcessor(), document);
        execution.preflightScope("/");

        // when
        execution.handlePatches(
                "/",
                execution.bundleForScope("/"),
                Collections.singletonList(JsonPatch.replace(
                        "/contracts",
                        recordingSurface(blue))),
                false);

        List<String> creatingEntryPaths =
                new ArrayList<String>(recorder.paths());
        boolean addedChannelIsLive = execution.bundleForScope("/")
                .channels().containsKey("watch");
        execution.handlePatches(
                "/",
                execution.bundleForScope("/"),
                Collections.singletonList(JsonPatch.add(
                        "/later",
                        new Node().value("visible"))),
                false);
        List<String> laterPaths =
                new ArrayList<String>(recorder.paths());

        // then
        assertTrue(creatingEntryPaths.isEmpty(),
                "the creating /contracts update is bound to the old surface");
        assertTrue(addedChannelIsLive);
        assertEquals(Collections.singletonList("/later"), laterPaths);
    }

    @Test
    void shouldFinishRemovedChannelDeliveryOnceAndRetireItLater() {
        // given
        RecordDocumentUpdateContractProcessor recorder =
                new RecordDocumentUpdateContractProcessor();
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(recorder);
        Node document = blue.yamlToNode(
                "name: Dynamic contracts\n" +
                "contracts:\n" + indent(recordingSurfaceYaml()));
        ProcessorInvocationState execution = new ProcessorInvocationState(
                blue.getDocumentProcessor(), document);
        execution.preflightScope("/");

        // when
        execution.handlePatches(
                "/",
                execution.bundleForScope("/"),
                Collections.singletonList(JsonPatch.remove("/contracts")),
                false);

        List<String> removingEntryPaths =
                new ArrayList<String>(recorder.paths());
        boolean removedChannelIsLive = execution.bundleForScope("/")
                .channels().containsKey("watch");
        execution.handlePatches(
                "/",
                execution.bundleForScope("/"),
                Collections.singletonList(JsonPatch.add(
                        "/later",
                        new Node().value("not delivered"))),
                false);
        List<String> laterPaths =
                new ArrayList<String>(recorder.paths());

        // then
        assertEquals(Collections.singletonList("/contracts"),
                removingEntryPaths,
                "the removed source surface completes its accepted delivery");
        assertFalse(removedChannelIsLive);
        assertEquals(Collections.singletonList("/contracts"),
                laterPaths,
                "later work observes the retired surface");
    }

    private static Node recordingSurface(Blue blue) {
        return blue.yamlToNode(recordingSurfaceYaml());
    }

    private static String recordingSurfaceYaml() {
        return "watch:\n" +
                "  type:\n" +
                "    blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "  path: /\n" +
                "record:\n" +
                "  channel: watch\n" +
                "  type:\n" +
                "    blueId: " + ProcessorTestTypeBlueIds.RECORD_DOCUMENT_UPDATE + "\n";
    }

    private static String indent(String value) {
        return "  " + value.replace("\n", "\n  ");
    }
}
