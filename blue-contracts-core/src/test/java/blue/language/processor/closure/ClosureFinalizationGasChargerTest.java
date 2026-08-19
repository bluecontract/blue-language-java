package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.PortableLimitExceededException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact ordering checks for the shared-meter finalization seam. */
final class ClosureFinalizationGasChargerTest {

    private static final String POLICY =
            "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String MASTER =
            "AUy8JhB5oRViCnC7CYpbL1hJdRY2sKDJUNaogzJ13xgR";
    private static final DocumentId A = new DocumentId("simple-a");
    private static final DocumentId B = new DocumentId("simple-b");
    private static final DocumentId C = new DocumentId("simple-c");
    private static final DocumentId D = new DocumentId("simple-d");

    @Test
    void chargesBoundaryLanguageTraceAndMembersOnOneSharedLedger() {
        Map<DocumentId, Node> bodies = bodies();
        List<ManagedOccurrenceBinding> bindings = bindings();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                bodies.keySet(), bindings);
        ComponentFinalizationResult result =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                generations(),
                                bodies,
                                bindings));
        ClosureFinalizationGasCharger charger =
                new ClosureFinalizationGasCharger();
        Set<String> established = new LinkedHashSet<String>();

        try (DocumentProcessor owner = DocumentProcessor.builder().build();
             ManagedDocumentStepProcessor meter =
                     new ManagedDocumentStepProcessor(owner)) {
            ClosureFinalizationGasCharger.FinalizationFrame frame =
                    charger.beginFinalization(
                            meter,
                            result.finalizedGraph(),
                            result.componentGenerations(),
                            work());
            charger.finishFinalization(
                    frame,
                    bodies,
                    result,
                    established,
                    Collections.<String>emptySet());

            List<GasTraceEntry> trace = meter.processorGasTrace();
            assertEquals(20, trace.size());
            assertEntry(trace.get(0),
                    "processor", "tentativeComponentFinalization", 1L,
                    "work.0.finalization.finalization-boundary");
            assertEntry(trace.get(7),
                    "semantic", "sortComparison", 1L,
                    "work.0.finalization.preliminary-sort.0");
            assertEntry(trace.get(8),
                    "semantic", "scalarComparison", 1L,
                    "work.0.finalization.preliminary-compare.0");
            assertEntry(trace.get(9),
                    "semantic", "textBlockExamined", 2L,
                    "work.0.finalization.preliminary-text.0");
            assertEntry(trace.get(16),
                    "semantic", "nodeIdentityEstablished", 1L,
                    "work.0.finalization.master.node-established");
            assertEntry(trace.get(17),
                    "semantic", "listFoldStepRecomputed", 2L,
                    "work.0.finalization.master.list-fold");
            assertEntry(trace.get(18),
                    "processor", "cyclicMemberFinalized", 1L,
                    "work.0.finalization.member-finalized.0");
            assertEntry(trace.get(19),
                    "processor", "cyclicMemberFinalized", 1L,
                    "work.0.finalization.member-finalized.1");
            for (int index = 0; index < trace.size(); index++) {
                assertEquals(index, trace.get(index).sequence());
                assertEquals(Long.valueOf(1L),
                        trace.get(index).componentGeneration());
                assertEquals(hash('3'),
                        trace.get(index).workOccurrenceId());
            }
            assertEquals(A.value(), trace.get(18).documentId());
            assertEquals(B.value(), trace.get(19).documentId());
            assertThrows(
                    IllegalStateException.class,
                    () -> charger.finishFinalization(
                            frame,
                            result,
                            established,
                            Collections.<String>emptySet()));
        }
    }

    @Test
    void interleavesEachChangedCyclicBoundaryWithOnlyItsLanguageTrace() {
        Map<DocumentId, Node> bodies = twoCycleBodies();
        List<ManagedOccurrenceBinding> bindings = twoCycleBindings();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                bodies.keySet(), bindings);
        ComponentFinalizationResult result =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                fourGenerations(),
                                bodies,
                                bindings));
        ClosureFinalizationGasCharger charger =
                new ClosureFinalizationGasCharger();

        try (DocumentProcessor owner = DocumentProcessor.builder().build();
             ManagedDocumentStepProcessor meter =
                     new ManagedDocumentStepProcessor(owner)) {
            ClosureFinalizationGasCharger.FinalizationFrame frame =
                    charger.beginFinalization(
                            meter,
                            result.finalizedGraph(),
                            result.componentGenerations(),
                            work());
            charger.finishFinalization(
                    frame,
                    bodies,
                    result,
                    new LinkedHashSet<String>(),
                    Collections.<String>emptySet());

            List<GasTraceEntry> trace = meter.processorGasTrace();
            List<Integer> boundaries = new java.util.ArrayList<Integer>();
            for (int index = 0; index < trace.size(); index++) {
                if ("tentativeComponentFinalization".equals(
                        trace.get(index).counter())) {
                    boundaries.add(Integer.valueOf(index));
                }
            }
            assertEquals(2, boundaries.size());
            assertEquals(Integer.valueOf(0), boundaries.get(0));
            int secondBoundary = boundaries.get(1).intValue();
            assertEquals("cyclicMemberFinalized",
                    trace.get(secondBoundary - 1).counter());
            assertEquals("semantic",
                    trace.get(secondBoundary + 1).namespace());
            assertEquals("work.0.finalization.component.0.finalization-boundary",
                    trace.get(0).reason());
            assertEquals("work.0.finalization.component.1.finalization-boundary",
                    trace.get(secondBoundary).reason());
        }
    }

    @Test
    void rejectsAComponentBoundaryBeforePublishingItsLanguageTrace() {
        Map<DocumentId, Node> bodies = bodies();
        List<ManagedOccurrenceBinding> bindings = bindings();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                bodies.keySet(), bindings);
        ComponentFinalizationResult result =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                generations(),
                                bodies,
                                bindings));
        ClosureFinalizationGasCharger charger =
                new ClosureFinalizationGasCharger();
        Set<String> established = new LinkedHashSet<String>();

        try (DocumentProcessor owner = DocumentProcessor.builder()
                .gasLimit(19L)
                .build();
             ManagedDocumentStepProcessor meter =
                     new ManagedDocumentStepProcessor(owner)) {
            ClosureFinalizationGasCharger.FinalizationFrame frame =
                    charger.beginFinalization(
                            meter,
                            result.finalizedGraph(),
                            result.componentGenerations(),
                            work());
            GasLimitExceededException rejected = assertThrows(
                    GasLimitExceededException.class,
                    () -> charger.finishFinalization(
                            frame,
                            bodies,
                            result,
                            established,
                            Collections.<String>emptySet()));

            assertEquals("tentativeComponentFinalization",
                    rejected.counter());
            assertEquals(Long.valueOf(0L), rejected.chargeContext()
                    .finalizationOrdinal());
            assertEquals(hash('3'), rejected.chargeContext()
                    .workOccurrenceId());
            assertTrue(rejected.chargeContext()
                    .finalizationComponentIdentity().startsWith("sha256:"));
            assertTrue(meter.processorGasTrace().isEmpty());
            assertTrue(established.isEmpty());
        }
    }

    @Test
    void retainsCompletedReceiptWhenTheNextComponentBoundaryRejects() {
        Map<DocumentId, Node> bodies = twoCycleBodies();
        List<ManagedOccurrenceBinding> bindings = twoCycleBindings();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                bodies.keySet(), bindings);
        ComponentFinalizationResult result =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                fourGenerations(),
                                bodies,
                                bindings));
        long firstComponentGas;
        try (DocumentProcessor owner = DocumentProcessor.builder().build();
             ManagedDocumentStepProcessor meter =
                     new ManagedDocumentStepProcessor(owner)) {
            ClosureFinalizationGasCharger charger =
                    new ClosureFinalizationGasCharger();
            ClosureFinalizationGasCharger.FinalizationFrame frame =
                    charger.beginFinalization(
                            meter,
                            result.finalizedGraph(),
                            result.componentGenerations(),
                            work());
            charger.finishFinalization(
                    frame,
                    bodies,
                    result,
                    new LinkedHashSet<String>(),
                    Collections.<String>emptySet());
            firstComponentGas = 0L;
            for (GasTraceEntry entry : meter.processorGasTrace()) {
                if ("work.0.finalization.component.1.finalization-boundary"
                        .equals(entry.reason())) {
                    break;
                }
                firstComponentGas += entry.subtotal();
            }
        }

        ClosureExecutionRecorder recorder =
                new ClosureExecutionRecorder(hash('9'));
        final ClosureFinalizationGasCharger.CyclicFinalizationPlan plan =
                ClosureFinalizationGasCharger.plan(
                        Collections.<ComponentSnapshot>emptyList(),
                        result,
                        ClosureValueSupport.MAX_SAFE_INTEGER);
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .gasLimit(firstComponentGas + 19L)
                .build();
             ManagedDocumentStepProcessor meter =
                     new ManagedDocumentStepProcessor(owner)) {
            ClosureFinalizationGasCharger charger =
                    new ClosureFinalizationGasCharger();
            ClosureFinalizationGasCharger.FinalizationFrame frame =
                    charger.beginFinalization(
                            meter,
                            result.finalizedGraph(),
                            result.componentGenerations(),
                            work(),
                            0L,
                            plan.memberSets());
            GasLimitExceededException rejected = assertThrows(
                    GasLimitExceededException.class,
                    () -> charger.finishFinalization(
                            frame,
                            bodies,
                            result,
                            new LinkedHashSet<String>(),
                            Collections.<String>emptySet(),
                            new ClosureFinalizationGasCharger
                                    .ComponentCompletion() {
                                @Override
                                public void completed(
                                        FinalizedComponentEvidence evidence) {
                                    recordReceipt(
                                            recorder,
                                            evidence,
                                            plan.canonicalBytes(evidence));
                                }
                            }));

            assertEquals(Long.valueOf(1L), rejected.chargeContext()
                    .finalizationOrdinal());
            assertEquals(result.components().get(1).component()
                            .componentIdentity(),
                    rejected.chargeContext()
                            .finalizationComponentIdentity());
            assertEquals(1, recorder.snapshot(null)
                    .tentativeFinalizations().size());
            assertEquals(result.components().get(0)
                            .cyclicFinalization().masterBlueId(),
                    recorder.snapshot(null).tentativeFinalizations()
                            .get(0).masterBlueId());
            List<GasTraceEntry> admitted = meter.processorGasTrace();
            assertEquals("cyclicMemberFinalized",
                    admitted.get(admitted.size() - 1).counter());
            for (GasTraceEntry entry : admitted) {
                assertTrue(!entry.reason().startsWith(
                        "work.0.finalization.component.1.preliminary"));
            }
        }
    }

    @Test
    void rejectsAnOversizedLaterComponentBeforeAnyBoundaryOrReceipt() {
        Map<DocumentId, Node> bodies = twoCycleBodies();
        bodies.put(C, cycleBody(repeat('x', 2048), "d", MASTER + "#1"));
        List<ManagedOccurrenceBinding> bindings = twoCycleBindings();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                bodies.keySet(), bindings);
        ComponentFinalizationResult result =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                fourGenerations(),
                                bodies,
                                bindings));
        ClosureFinalizationGasCharger.CyclicFinalizationPlan admitted =
                ClosureFinalizationGasCharger.plan(
                        Collections.<ComponentSnapshot>emptyList(),
                        result,
                        ClosureValueSupport.MAX_SAFE_INTEGER);
        long firstBytes = admitted.canonicalBytes(
                result.components().get(0));
        long secondBytes = admitted.canonicalBytes(
                result.components().get(1));
        assertTrue(secondBytes > firstBytes);

        ClosureExecutionRecorder recorder =
                new ClosureExecutionRecorder(hash('8'));
        try (DocumentProcessor owner = DocumentProcessor.builder().build();
             ManagedDocumentStepProcessor meter =
                     new ManagedDocumentStepProcessor(owner)) {
            PortableLimitExceededException rejected = assertThrows(
                    PortableLimitExceededException.class,
                    () -> ClosureFinalizationGasCharger.plan(
                            Collections.<ComponentSnapshot>emptyList(),
                            result,
                            firstBytes));

            assertEquals("cyclicCanonicalBytesPerComponent",
                    rejected.limitName());
            assertEquals(
                    blue.language.processor.ProcessorErrorCategory
                            .CyclicComponentCanonicalBytesExceeded,
                    rejected.diagnostic().category());
            assertEquals(secondBytes, rejected.observed());
            assertTrue(meter.processorGasTrace().isEmpty());
            assertTrue(recorder.snapshot(null)
                    .tentativeFinalizations().isEmpty());
        }
    }

    private static void recordReceipt(
            ClosureExecutionRecorder recorder,
            FinalizedComponentEvidence evidence,
            long canonicalBytes) {
        LinkedHashMap<DocumentId, String> members =
                new LinkedHashMap<DocumentId, String>();
        ComponentSnapshot component = evidence.component();
        for (int index = 0;
                index < component.orderedMemberDocumentIds().size();
                index++) {
            members.put(
                    component.orderedMemberDocumentIds().get(index),
                    component.orderedMemberBlueIds().get(index));
        }
        recorder.finalization(new TentativeFinalization(
                recorder.finalizationCount(),
                TentativeFinalization.Boundary.work(0L),
                evidence.cyclicFinalization().masterBlueId(),
                members,
                canonicalBytes));
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static void assertEntry(
            GasTraceEntry entry,
            String namespace,
            String counter,
            long quantity,
            String reason) {
        assertEquals(namespace, entry.namespace());
        assertEquals(counter, entry.counter());
        assertEquals(quantity, entry.quantity());
        assertEquals(reason, entry.reason());
    }

    private static Map<DocumentId, Node> bodies() {
        LinkedHashMap<DocumentId, Node> result =
                new LinkedHashMap<DocumentId, Node>();
        result.put(A, new Node().properties(
                "documentId", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "memberIdentity", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "b", reference(MASTER + "#1"),
                "contracts", reference(
                        "AKdg7JuRiCbPdRARLfWhCoSFQz4htgjc2pcDPWkNPfQJ")));
        result.put(B, new Node().properties(
                "documentId", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "memberIdentity", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "a", reference(MASTER + "#0"),
                "contracts", reference(
                        "F4GdSvomgpBDpomh3VuFEg3L6yu2gLsBQeCEyGmYpeuz")));
        return result;
    }

    private static List<ManagedOccurrenceBinding> bindings() {
        return Arrays.asList(
                binding(A, "/b", B, MASTER + "#1"),
                binding(B, "/a", A, MASTER + "#0"));
    }

    private static Map<DocumentId, Node> twoCycleBodies() {
        LinkedHashMap<DocumentId, Node> result =
                new LinkedHashMap<DocumentId, Node>();
        result.put(A, cycleBody("a", "b", MASTER + "#1"));
        result.put(B, cycleBody("b", "a", MASTER + "#0"));
        result.put(C, cycleBody("c", "d", MASTER + "#1"));
        result.put(D, cycleBody("d", "c", MASTER + "#0"));
        return result;
    }

    private static Node cycleBody(
            String name,
            String edge,
            String expectedTargetBlueId) {
        return new Node().name(name).properties(
                edge, reference(expectedTargetBlueId));
    }

    private static List<ManagedOccurrenceBinding> twoCycleBindings() {
        return Arrays.asList(
                binding(A, "/b", B, MASTER + "#1"),
                binding(B, "/a", A, MASTER + "#0"),
                binding(C, "/d", D, MASTER + "#1"),
                binding(D, "/c", C, MASTER + "#0"));
    }

    private static ManagedOccurrenceBinding binding(
            DocumentId source,
            String path,
            DocumentId target,
            String expectedTargetBlueId) {
        ScopeAddress address = ScopeAddress.embedded(path, 1L);
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        return new ManagedOccurrenceBinding(
                identities.managedOccurrenceIdentity(
                        source, address, target, POLICY),
                identities.managedOccurrenceBindingIdentity(
                        source,
                        address,
                        target,
                        expectedTargetBlueId,
                        POLICY),
                POLICY,
                source,
                address,
                target,
                expectedTargetBlueId,
                true,
                null);
    }

    private static Map<DocumentId, Long> generations() {
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        result.put(A, Long.valueOf(1L));
        result.put(B, Long.valueOf(1L));
        return result;
    }

    private static Map<DocumentId, Long> fourGenerations() {
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        result.put(A, Long.valueOf(1L));
        result.put(B, Long.valueOf(1L));
        result.put(C, Long.valueOf(1L));
        result.put(D, Long.valueOf(1L));
        return result;
    }

    private static ClosureWorkOccurrence work() {
        return new ClosureWorkOccurrence(
                0L,
                WorkKind.EXTERNAL_DELIVERY,
                A,
                "source",
                null,
                null,
                hash('1'),
                hash('2'),
                hash('3'));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String hash(char digit) {
        StringBuilder value = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            value.append(digit);
        }
        return value.toString();
    }
}
