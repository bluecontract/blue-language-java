package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.ApplyBatchPatch;
import blue.language.processor.model.AssertDocumentUpdate;
import blue.language.processor.model.CutOffProbe;
import blue.language.processor.model.EmitEvents;
import blue.language.processor.model.IncrementProperty;
import blue.language.processor.model.MutateEmbeddedPaths;
import blue.language.processor.model.MutateEvent;
import blue.language.processor.model.ProcessingFailureMarker;
import blue.language.processor.model.RecordDocumentUpdate;
import blue.language.processor.model.RemoveIfPresent;
import blue.language.processor.model.RemoveProperty;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.SetPropertyOnEvent;
import blue.language.processor.model.TerminateScope;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.TestEventChannel;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.LanguageRuntimeAccess;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ProcessorTestSupport {

    private static final Class<?>[] TEST_CONTRACT_TYPES = new Class<?>[]{
            ApplyBatchPatch.class,
            AssertDocumentUpdate.class,
            CutOffProbe.class,
            EmitEvents.class,
            IncrementProperty.class,
            MutateEmbeddedPaths.class,
            MutateEvent.class,
            ProcessingFailureMarker.class,
            RecordDocumentUpdate.class,
            RemoveIfPresent.class,
            RemoveProperty.class,
            SetProperty.class,
            SetPropertyOnEvent.class,
            TerminateScope.class,
            TestEvent.class,
            TestEventChannel.class
    };

    private ProcessorTestSupport() {
    }

    static Blue blue() {
        return new Blue(testContractTypeProvider());
    }

    static Blue blue(NodeProvider provider) {
        return new Blue(providerWithTestContractTypes(provider));
    }

    static RuntimeWorkSession admissionRuntimeWorkSession(
            DocumentProcessor processor,
            ProcessingSnapshotManager snapshotManager,
            Node exactEvent) {
        DocumentProcessor owner = Objects.requireNonNull(
                processor, "processor");
        ProcessingSnapshotManager snapshots = Objects.requireNonNull(
                snapshotManager, "snapshotManager");
        Node event = Objects.requireNonNull(
                exactEvent, "exactEvent");
        LanguageRuntimeAccess languageRuntime = Objects.requireNonNull(
                owner.languageRuntimeAccess(),
                "processor.languageRuntimeAccess");
        RuntimeWorkSession session =
                new ProcessingGasContext(owner.newGasMeter())
                        .newAdmissionRuntimeWorkSession(
                                languageRuntime,
                                snapshots);
        session.carryExactInput(
                event,
                CheckpointIdentityCalculator.identity(
                        event,
                        languageRuntime));
        return session;
    }

    static NodeProvider providerWithTestContractTypes(NodeProvider provider) {
        return new SequentialNodeProvider(testContractTypeProvider(), provider);
    }

    static NodeProvider testContractTypeProvider() {
        return simpleNameTypeProvider(TEST_CONTRACT_TYPES);
    }

    static NodeProvider simpleNameTypeProvider(Class<?>... types) {
        Map<String, Node> nodesByBlueId = new LinkedHashMap<>();
        for (Class<?> type : types) {
            Node node = new Node().name(type.getSimpleName());
            String calculated = DirectBlueIdCalculator.calculateBlueId(node);
            TypeBlueId annotation = type.getAnnotation(TypeBlueId.class);
            if (annotation != null) {
                for (String blueId : annotation.value()) {
                    if (blueId != null && !blueId.isEmpty() && !blueId.equals(calculated)) {
                        throw new IllegalStateException(type.getName()
                                + " test type node hashes to " + calculated + ", not " + blueId);
                    }
                }
            }
            nodesByBlueId.put(calculated, node);
        }
        return blueId -> {
            Node node = nodesByBlueId.get(blueId);
            return node != null ? Collections.singletonList(node.clone()) : null;
        };
    }
}
