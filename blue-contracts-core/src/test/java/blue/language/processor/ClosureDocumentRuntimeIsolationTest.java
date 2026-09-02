package blue.language.processor;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Characterizes the execution boundary used by managed closure processing. */
final class ClosureDocumentRuntimeIsolationTest {

    @Test
    void shouldIsolateDocumentRuntimeStateWhileSharingInvocationGasContext() {
        ProcessingGasContext invocationGas =
                new ProcessingGasContext(new GasMeter());

        DocumentProcessingRuntime first = runtime(
                new Node().properties(
                        "name", new Node().value("first")),
                invocationGas);
        DocumentProcessingRuntime second = runtime(
                new Node().properties(
                        "name", new Node().value("second")),
                invocationGas);

        assertNotSame(first, second);
        assertNotSame(
                first.documentViewComponent(),
                second.documentViewComponent());
        assertNotSame(
                first.scopeRegistryComponent(),
                second.scopeRegistryComponent());
        assertSame(invocationGas, first.gasContextComponent());
        assertSame(invocationGas, second.gasContextComponent());
        assertSame(first.gasMeter(), second.gasMeter());
    }

    @Test
    void facadeSharesOneLedgerAndSemanticAdmissionMemoAcrossOrchestrationAndSteps() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            Map<String, Long> localLimits = new LinkedHashMap<String, Long>();
            localLimits.put("a", Long.valueOf(100000L));
            localLimits.put("b", Long.valueOf(100000L));
            try (ManagedDocumentStepRuntime session =
                         new ManagedDocumentStepRuntime(
                                 owner,
                                 100000L,
                                 localLimits,
                                 noOpContinuation())) {
                ProcessingGasContext exactContext =
                        session.sharedGasContext();
                Object admissionMemo =
                        exactContext.semanticAdmissionMemoIdentity();

                session.charge(
                        "processor",
                        "closureInvocation",
                        1L,
                        GasChargeContext.reason("closure-start"));
                ManagedDocumentStepOutcome first = session.execute(
                        initializationRequest("a", "work-a"));
                ManagedDocumentStepOutcome second = session.execute(
                        initializationRequest("b", "work-b"));

                assertEquals(100L, first.gasBefore());
                assertEquals(first.gasAfter(), second.gasBefore());
                assertEquals(second.gasAfter(), session.totalGas());
                assertEquals(100000L, session.gasLimit());
                assertSame(admissionMemo,
                        session.sharedGasContext()
                                .semanticAdmissionMemoIdentity());
                List<GasTraceEntry> trace = session.gasTrace();
                assertEquals("closureInvocation", trace.get(0).counter());
                assertEquals(null, trace.get(0).documentId());
                assertEquals("a", trace.get(1).documentId());
                boolean sawB = false;
                for (int index = 0; index < trace.size(); index++) {
                    assertEquals((long) index, trace.get(index).sequence());
                    if ("b".equals(trace.get(index).documentId())) {
                        sawB = true;
                    }
                }
                assertEquals(true, sawB);
            }
        }
    }

    private static ManagedDocumentStepRequest initializationRequest(
            String documentId,
            String workIdentity) {
        return new ManagedDocumentStepRequest(
                new Node().name(documentId),
                false,
                false,
                ManagedDocumentWorkKind.INITIALIZATION,
                "",
                new Node().name("initialize"),
                GasChargeContext.closure(
                        documentId,
                        "/",
                        Long.valueOf(0L),
                        Long.valueOf(1L),
                        null,
                        null,
                        workIdentity,
                        "document-step"));
    }

    private static ManagedDocumentStepContinuation noOpContinuation() {
        return new ManagedDocumentStepContinuation() {
            @Override
            public void afterPatch(
                    String scopePath,
                    Node currentDocument,
                    FrozenJsonPatch patch,
                    List<DocumentUpdateOccurrence> updates) {
            }

            @Override
            public void onApplicationEvent(
                    String scopePath,
                    String originContractKey,
                    ExactEventIdentityEvidence exactEvent) {
            }

            @Override
            public void onTerminationRequested(
                    String scopePath,
                    String cause,
                    String reason) {
            }
        };
    }

    private static DocumentProcessingRuntime runtime(
            Node document,
            ProcessingGasContext invocationGas) {
        return new DocumentProcessingRuntime(
                document,
                null,
                null,
                null,
                null,
                invocationGas,
                Collections.<String, java.util.List<String>>emptyMap(),
                false);
    }
}
