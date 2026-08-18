package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ManagedDocumentStepOutcome;
import blue.language.processor.ManagedDocumentStepRequest;
import blue.language.processor.ManagedDocumentStepRoute;
import blue.language.processor.ManagedDocumentStepRuntime;
import blue.language.processor.ManagedDocumentWorkKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps closed closure evidence onto the isolated processor-side runtime. */
final class ManagedDocumentStepProcessor
        implements DocumentStepProcessor, AutoCloseable {

    private final ManagedDocumentStepRuntime runtime;

    ManagedDocumentStepProcessor(DocumentProcessor owner) {
        this.runtime = new ManagedDocumentStepRuntime(
                Objects.requireNonNull(owner, "owner"));
    }

    ManagedDocumentStepProcessor(
            DocumentProcessor owner,
            ManagedDocumentStepContinuation continuation) {
        this.runtime = new ManagedDocumentStepRuntime(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(continuation, "continuation"));
    }

    ManagedDocumentStepProcessor(
            DocumentProcessor owner,
            ExecutionPolicy policy,
            ManagedDocumentStepContinuation continuation) {
        ExecutionPolicy admitted = Objects.requireNonNull(policy, "policy");
        this.runtime = new ManagedDocumentStepRuntime(
                Objects.requireNonNull(owner, "owner"),
                admitted.sharedLimit(),
                localLimits(admitted),
                Objects.requireNonNull(continuation, "continuation"));
    }

    @Override
    public LocalDocumentStepResult process(DocumentStepInput input) {
        DocumentStepInput admitted = Objects.requireNonNull(input, "input");
        ManagedDocumentSnapshot target = admitted.targetDocument();
        ManagedDocumentStepOutcome outcome = runtime.execute(
                new ManagedDocumentStepRequest(
                        target.document(),
                        target.initialized(),
                        target.terminated(),
                        map(admitted.work().kind()),
                        admitted.work().channelKey(),
                        admitted.exactPayload(),
                        admitted.occurrenceEvent(),
                        admitted.processorPatch(),
                        GasChargeContext.closure(
                                target.documentId().value(),
                                "/",
                                Long.valueOf(0L),
                                Long.valueOf(target.componentGeneration()),
                                admitted.work().channelKey().isEmpty()
                                        ? null
                                        : admitted.work().channelKey(),
                                null,
                                admitted.work().workIdentity(),
                                "managed-document-step")));
        return new LocalDocumentStepResult(
                target.documentId(),
                admitted.work().workIdentity(),
                target.blueId(),
                outcome.resultingBody(),
                outcome.emittedEvents(),
                outcome.orderedPatches(),
                outcome.gasBefore(),
                outcome.gasAfter(),
                outcome.identityAffecting());
    }

    private static ManagedDocumentWorkKind map(WorkKind kind) {
        return ManagedDocumentWorkKind.valueOf(
                Objects.requireNonNull(kind, "kind").name());
    }

    List<ManagedDocumentStepRoute> classifyTriggeredEventRoutes(
            Node exactDocument,
            Node exactEvent) {
        return runtime.classifyTriggeredEventRoutes(
                exactDocument, exactEvent);
    }

    List<ManagedDocumentStepRoute> classifyEmbeddedEventRoutes(
            Node exactContainingDocument,
            String exactSourcePath,
            Node exactEvent,
            String exactEventBlueId) {
        return runtime.classifyEmbeddedEventRoutes(
                exactContainingDocument,
                exactSourcePath,
                exactEvent,
                exactEventBlueId);
    }

    List<ManagedDocumentStepRoute> classifyDocumentUpdateRoutes(
            Node exactDocument,
            DocumentUpdateOccurrence occurrence) {
        return runtime.classifyDocumentUpdateRoutes(
                exactDocument, occurrence);
    }

    void charge(
            String namespace,
            String counter,
            long quantity,
            GasChargeContext context) {
        runtime.charge(namespace, counter, quantity, context);
    }

    long totalGas() {
        return runtime.totalGas();
    }

    List<GasTraceEntry> processorGasTrace() {
        return runtime.gasTrace();
    }

    private static Map<String, Long> localLimits(
            ExecutionPolicy policy) {
        LinkedHashMap<String, Long> result =
                new LinkedHashMap<String, Long>();
        for (Map.Entry<DocumentId, Long> entry
                : policy.localLimits().entrySet()) {
            result.put(entry.getKey().value(), entry.getValue());
        }
        return result;
    }

    @Override
    public void close() {
        runtime.close();
    }
}
