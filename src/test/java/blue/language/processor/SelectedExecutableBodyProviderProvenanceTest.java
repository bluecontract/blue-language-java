package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.conformance.MockHandler;
import blue.language.processor.conformance.MockTypeBlueIds;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedExecutableBodyProviderProvenanceTest {

    @Test
    void selectedBodyUsesActiveSnapshotManagerInsteadOfMatchingBlueProvider() {
        Node body = new Node().properties(
                "provenance", new Node().value("active-snapshot-manager"));
        String bodyBlueId =
                BlueIdCalculator.calculateBlueId(body);
        ActiveProviderManager activeManager =
                new ActiveProviderManager(bodyBlueId, body);

        AtomicInteger matchingProviderFetches =
                new AtomicInteger();
        Blue matchingBlue = new Blue(blueId -> {
            if (bodyBlueId.equals(blueId)) {
                matchingProviderFetches.incrementAndGet();
                return Collections.singletonList(
                        new Node().value(
                                "wrong matching-provider content"));
            }
            return null;
        });
        CapturingMockHandlerProcessor handlerProcessor =
                new CapturingMockHandlerProcessor();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(handlerProcessor)
                        .build();
        DocumentProcessor owner = DocumentProcessor.builder()
                .withRegistry(registry)
                .withSnapshotManager(activeManager)
                .withMatchingService(
                        new ContractMatchingService(matchingBlue))
                .build();

        MockHandler selected = new MockHandler();
        selected.setTypeBlueId(
                MockTypeBlueIds.MOCK_HANDLER);
        selected.setChannelKey("events");
        selected.setResult(
                new Node().blueId(bodyBlueId));
        Node selectedNode = new Node()
                .type(new Node().blueId(
                        MockTypeBlueIds.MOCK_HANDLER))
                .properties("channel",
                        new Node().value("events"))
                .properties("result",
                        new Node().blueId(bodyBlueId));
        ContractBundle bundle = ContractBundle.builder()
                .addHandler(
                        "selected",
                        selected,
                        FrozenNode.fromResolvedNode(
                                selectedNode),
                        Collections.singletonList("result"))
                .build();
        ResolvedSnapshot invocationSnapshot =
                activeManager.fromDocument(new Node());
        ProcessorEngine.Execution execution =
                new ProcessorEngine.Execution(
                        owner, invocationSnapshot);
        ChannelRunner runner = new ChannelRunner(
                owner,
                execution,
                execution.runtime(),
                new CheckpointManager(execution.runtime()));

        assertTrue(runner.runHandlers(
                "/", bundle, "events", new Node()));

        assertEquals(1, activeManager.materializations);
        assertEquals(0, matchingProviderFetches.get());
        assertNotNull(handlerProcessor.executedResult);
        assertEquals("active-snapshot-manager",
                handlerProcessor.executedResult
                        .getAsText("/provenance"));
    }

    @Test
    void activeRuntimeMaterializerRevalidatesManagerOwnedExactResult() {
        Node body = new Node().value("owned");
        String bodyBlueId =
                BlueIdCalculator.calculateBlueId(body);
        ActiveProviderManager manager =
                new ActiveProviderManager(bodyBlueId, body);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node(), null, manager);
        FrozenNode reference =
                FrozenNode.fromResolvedNode(
                        new Node().blueId(bodyBlueId));

        FrozenNode materialized =
                runtime.materializeSelectedExecutableReference(
                        reference);

        assertEquals(1, manager.materializations);
        assertEquals(bodyBlueId,
                materialized.blueId());
        assertTrue(materialized.isStrictCanonical());
    }

    @Test
    void runtimeMaterializationFailsClosedWithoutSnapshotManager() {
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(new Node());
        FrozenNode reference =
                FrozenNode.fromResolvedNode(
                        new Node().blueId(
                                BlueIdCalculator.calculateBlueId(
                                        new Node().value("body"))));

        assertThrows(IllegalStateException.class,
                () -> runtime
                        .materializeSelectedExecutableReference(
                        reference));
    }

    @Test
    void runtimeRejectsManagerContentThatDoesNotMatchSelectedBodyReference() {
        Node exact = new Node().value("exact");
        String bodyBlueId =
                BlueIdCalculator.calculateBlueId(exact);
        ActiveProviderManager manager =
                new ActiveProviderManager(
                        bodyBlueId,
                        new Node().value("expanded-or-wrong"));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node(), null, manager);

        ProcessorFailureException failure =
                assertThrows(
                        ProcessorFailureException.class,
                        () -> runtime
                                .materializeSelectedExecutableReference(
                                        FrozenNode.fromNode(
                                                new Node().blueId(
                                                        bodyBlueId))));

        assertEquals(
                ProcessorErrorCategory
                        .ProviderBlueIdMismatch,
                failure.errorCategory());
    }

    private static final class CapturingMockHandlerProcessor
            implements HandlerProcessor<MockHandler> {
        private Node executedResult;

        @Override
        public Class<MockHandler> contractType() {
            return MockHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("result");
        }

        @Override
        public void execute(
                MockHandler contract,
                ProcessorExecutionContext context) {
            executedResult = contract.getResult();
        }
    }

    private static final class ActiveProviderManager
            implements ProcessingSnapshotManager {
        private final String bodyBlueId;
        private final FrozenNode materializedBody;
        private int materializations;

        private ActiveProviderManager(
                String bodyBlueId,
                Node body) {
            this.bodyBlueId = bodyBlueId;
            this.materializedBody =
                    FrozenNode.fromResolvedNode(body);
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            Node canonical = document.clone();
            return new ResolvedSnapshot(
                    canonical,
                    canonical.clone(),
                    BlueIdCalculator.calculateBlueId(
                            canonical));
        }

        @Override
        public FrozenNode materializeVerifiedReference(
                FrozenNode reference) {
            assertTrue(reference.isReferenceOnly());
            assertEquals(bodyBlueId,
                    reference.getReferenceBlueId());
            materializations++;
            return materializedBody;
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return snapshot;
        }
    }
}
