package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.ExactEventIdentityEvidenceStorageCodec;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.snapshot.FrozenNode;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.ExactNodeStorageCodec;
import blue.language.snapshot.FrozenNodeStorageCodec;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class ClosureEventStorageTest {
    private static final int BYTES = 16 * 1024 * 1024;
    private static final ExactNodeStorageCodec NODES = new ExactNodeStorageCodec(BYTES, 128);
    private static final FrozenNodeStorageCodec FROZEN = new FrozenNodeStorageCodec(BYTES, 128);
    private static final ExactEventIdentityEvidenceStorageCodec EVENTS = new ExactEventIdentityEvidenceStorageCodec(BYTES, 128);

    @Test void typedSourceFieldRestoresWithoutConsultingTheDenyingProvider() {
        Node parentType = new Node().name("Stored event parent type");
        String parentId = DirectBlueIdCalculator.calculateBlueId(parentType);
        Node event = new Node().type(new Node().type(new Node().blueId(parentId))
                .name("Inline stored event type")).properties("payload", new Node().value("retained"));
        AtomicInteger admissionReads = new AtomicInteger();
        ExactEventIdentityEvidence admitted;
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
            admissionReads.incrementAndGet();
            return parentId.equals(id) ? Collections.singletonList(parentType.clone()) : Collections.emptyList();
        }).build(); BlueContracts contracts = BlueContracts.builder(language.processing()).build()) {
            String id = contracts.runtimeAccess().languageRuntime().calculateSourceDocumentBlueId(event);
            admitted = ExactEventIdentityEvidence.verify(contracts.runtimeAccess(), event, id, null);
        }
        assertTrue(admissionReads.get() > 0, "Actual typed Source admission required the parent provider");
        ManagedRootEventOccurrence occurrence = new ManagedRootEventOccurrence(0, 0, new DocumentId("source"),
                hash('a'), admitted, true);
        byte[] bytes = NODES.encodeEnvelope("event-test", out ->
                new ClosureResultStorageValues.Writer(out, NODES, EVENTS)
                        .w(occurrence));
        AtomicInteger deniedReads = new AtomicInteger();
        try (BlueLanguage denying = BlueLanguage.builder().nodeProvider(id -> {
            deniedReads.incrementAndGet();
            throw new AssertionError("Stored evidence must not consult provider " + id);
        }).build(); BlueContracts contracts = BlueContracts.builder(denying.processing()).build()) {
            ManagedRootEventOccurrence restored = NODES.decodeEnvelope(bytes, "event-test", in ->
                    new ClosureResultStorageValues.Reader(in, NODES, EVENTS).r());
            assertEquals(0, deniedReads.get());
            assertEquals(admitted.eventBlueId(), restored.eventBlueId());
            assertArrayEquals(FROZEN.encode(admitted.frozenEvent()),
                    FROZEN.encode(restored.exactEventIdentityEvidence().frozenEvent()));
            assertThrows(AssertionError.class, () -> ExactEventIdentityEvidence.verify(
                    contracts.runtimeAccess(), event, admitted.eventBlueId(), null),
                    "The unchanged external verification path still requires its provider");
            assertTrue(deniedReads.get() > 0);
        }
    }

    @Test void actualRootedResultRetainsOriginalEventFrozenConstruction() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = CompositionReactionCycleTest.ring(fixture, "stored-event", 3, 2);
            Map<DocumentId, String> histories = new LinkedHashMap<>();
            graph.managedDocuments().forEach(d -> histories.put(d.documentId(), hash('c')));
            ClosureProcessResult result = fixture.admit(fixture.admission(graph, 100_000L)
                    .withRootedContext(RootedProcessingContext.derive(graph, new DocumentId("d0"), histories),
                            hash('d'))).processResult();
            assertTrue(result.commits(), diagnostic(result));
            ClosureProcessResultStorageCodec codec = new ClosureProcessResultStorageCodec(BYTES, 128);
            byte[] stored = codec.encode(result, id -> { throw new AssertionError("External proof lookup"); });
            try (BlueLanguage denying = BlueLanguage.builder().nodeProvider(id -> {
                throw new AssertionError("Result storage provider lookup");
            }).build(); BlueContracts contracts = BlueContracts.builder(denying.processing()).build()) {
                ClosureProcessResult restored = codec.decode(stored, contracts.runtimeAccess());
                assertArrayEquals(stored, codec.encode(codec.decode(stored)));
                int events = 0;
                for (int i = 0; i < result.managedTransitionReceipts().size(); i++) {
                    java.util.List<ManagedRootEventOccurrence> original = result.managedTransitionReceipts().get(i).emittedRootEvents();
                    java.util.List<ManagedRootEventOccurrence> cold = restored.managedTransitionReceipts().get(i).emittedRootEvents();
                    assertEquals(original.size(), cold.size());
                    for (int j = 0; j < original.size(); j++) {
                        events++;
                        assertArrayEquals(FROZEN.encode(original.get(j).exactEventIdentityEvidence().frozenEvent()),
                                FROZEN.encode(cold.get(j).exactEventIdentityEvidence().frozenEvent()),
                                "Storage must not replace the invocation-issued frozen construction");
                    }
                }
                assertTrue(events > 0);
                for (int i = 0; i < result.publicEvents().size(); i++) {
                    assertArrayEquals(FROZEN.encode(result.publicEvents().get(i).exactEventIdentityEvidence().frozenEvent()),
                            FROZEN.encode(restored.publicEvents().get(i).exactEventIdentityEvidence().frozenEvent()));
                }
            }
        }
    }

    @Test void actualRootedTypedSourceResultRestoresAfterItsProviderCloses() {
        Node parentType = new Node().name("Full rooted event parent type");
        String parentId = DirectBlueIdCalculator.calculateBlueId(parentType);
        Node event = new Node().type(new Node().type(new Node().blueId(parentId))
                .name("Inline full rooted event type")).properties("payload", new Node().value("original Source"));
        AtomicInteger sourceReads = new AtomicInteger();
        AtomicReference<FrozenNode> admittedFrozen = new AtomicReference<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(HANDLER_ID, HANDLER, new HandlerProcessor<CampaignHandler>() {
                    public Class<CampaignHandler> contractType() { return CampaignHandler.class; }
                    public void execute(CampaignHandler ignored, ProcessorExecutionContext context) {
                        context.emitEvent(event.clone());
                        blue.language.processor.ExactBlueValue admitted = context.semanticOutputBoundary().admit(event.clone());
                        admittedFrozen.set(admitted.frozenValue());
                        context.emitEvent(admitted);
                    }
                }).build();
        ClosureProcessResult original;
        byte[] stored;
        ClosureProcessResultStorageCodec codec = new ClosureProcessResultStorageCodec(BYTES, 128);
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
            if (parentId.equals(id)) {
                sourceReads.incrementAndGet();
                return Collections.singletonList(parentType.clone());
            }
            if (HANDLER_ID.equals(id)) return Collections.singletonList(HANDLER.clone());
            java.util.List<Node> core = blue.language.registry.BlueCoreTypeRegistry.INSTANCE.verifiedProvider().fetchByBlueId(id);
            return core == null || core.isEmpty() ? blue.language.processor.registry.BlueRuntimeTypeRegistry.getDefault().asProvider()
                    .fetchByBlueId(id) : core;
        }).build(); BlueContracts backing = BlueContracts.builder(language.processing()).runtimeRegistry(registry).build();
             DocumentProcessor processor = DocumentProcessor.builder().runtimeRegistry(registry)
                     .runtimeRegistryIdentity(registry.generationIdentity()).runtimeAccess(backing.runtimeAccess()).build();
             BlueClosureContracts contracts = new BlueClosureContracts(processor)) {
            ClosureEnvironment environment = ClosureEvidenceFactory.environment(processor, hash('a'),
                    RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY, "stored-typed-documents",
                    "stored-typed-bindings", "stored-typed-provider", "stored-typed-order", "stored-typed-limits",
                    GasSchedule.contracts10().portableLimits());
            DocumentId root = new DocumentId("typed-source-root");
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(root, document("typed-source-root")),
                    Collections.emptyList(), root);
            ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(graph,
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                            "stored-typed-admission", null, null, "stored-typed-policy"), null,
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "stored-typed-gas"), environment)
                    .withRootedContext(RootedProcessingContext.derive(graph, root,
                            Collections.singletonMap(root, hash('c'))), hash('d'));
            original = contracts.admitClosureWithLifecycleQueue(input).processResult();
            assertTrue(original.commits(), diagnostic(original));
            assertNotNull(original.rootedProjection());
            assertEquals(2, original.publicEvents().size());
            assertTrue(sourceReads.get() > 0, "The actual handler's typed Source required external parent content");
            assertFalse(original.publicEvents().get(0).event().getType().isReferenceOnly(),
                    "The retained event must remain materialized Source, not a canonical reference substitute");
            assertArrayEquals(FROZEN.encode(admittedFrozen.get()),
                    FROZEN.encode(original.publicEvents().get(1).exactEventIdentityEvidence().frozenEvent()),
                    "The second emission must carry the actual semantic-output admitted Frozen capability");
            stored = codec.encode(original, id -> { throw new AssertionError("Proof callback during storage"); });
        }
        AtomicInteger denied = new AtomicInteger();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
            denied.incrementAndGet(); throw new AssertionError("Provider after cold result restore " + id);
        }).build(); BlueContracts contracts = BlueContracts.builder(language.processing()).build()) {
            ClosureProcessResult cold = codec.decode(stored, contracts.runtimeAccess());
            assertEquals(0, denied.get());
            assertEquals(original.totalGas(), cold.totalGas());
            assertEquals(original.gasTraceIdentity(), cold.gasTraceIdentity());
            assertEquals(original.managedTransitionReceiptsIdentity(), cold.managedTransitionReceiptsIdentity());
            assertEquals(original.rootedProjection().companionIdentity(), cold.rootedProjection().companionIdentity());
            assertArrayEquals(stored, codec.encode(cold));
            for (int i = 0; i < original.publicEvents().size(); i++) {
                assertArrayEquals(FROZEN.encode(original.publicEvents().get(i).exactEventIdentityEvidence().frozenEvent()),
                        FROZEN.encode(cold.publicEvents().get(i).exactEventIdentityEvidence().frozenEvent()));
                assertArrayEquals(FROZEN.encode(original.managedTransitionReceipts().get(0).emittedRootEvents().get(i)
                                .exactEventIdentityEvidence().frozenEvent()),
                        FROZEN.encode(cold.managedTransitionReceipts().get(0).emittedRootEvents().get(i)
                                .exactEventIdentityEvidence().frozenEvent()));
            }
            assertThrows(AssertionError.class, () -> ExactEventIdentityEvidence.verify(contracts.runtimeAccess(),
                    event, original.publicEvents().get(0).eventBlueId(), null));
            assertTrue(denied.get() > 0, "Normal external verification still performs the necessary lookup");
        }
    }
}
