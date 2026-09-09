package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.*;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Small application-neutral runtime; expected transitions live in the tests. */
final class CompositionCampaignFixture implements AutoCloseable {
    static final Node HANDLER = new Node().name("R2 composition handler");
    static final String HANDLER_ID = id(HANDLER);
    final List<String> initialized = new ArrayList<>();
    final List<String> reactions = new ArrayList<>();
    final Map<String, Node> exact = new LinkedHashMap<>();
    final DocumentProcessor owner;
    final ClosureEnvironment environment;
    int failAt = -1;
    boolean loop;
    String removeMember;
    boolean removeChannel;
    ClosureImplementationEvidence evidence;

    CompositionCampaignFixture() { this(false); }

    CompositionCampaignFixture(boolean rooted) {
        owner = DocumentProcessor.builder().registerContractProcessor(HANDLER_ID, HANDLER,
                new HandlerProcessor<CampaignHandler>() {
                    public Class<CampaignHandler> contractType() { return CampaignHandler.class; }
                    public void execute(CampaignHandler contract, ProcessorExecutionContext context) {
                        run(context);
                    }
                }).nodeProvider(id -> exact.containsKey(id)
                        ? Collections.singletonList(exact.get(id).clone()) : Collections.emptyList()).build();
        environment = ClosureEvidenceFactory.environment(owner, hash('a'),
                rooted ? RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY : hash('b'),
                "r2-document-lineage", "r2-occurrence-binding", "r2-provider",
                "r2-source-order", "r2-limits", GasSchedule.contracts10().portableLimits());
    }

    private void run(ProcessorExecutionContext context) {
        String label = context.documentAt("/label").getValue().toString();
        if ("init".equals(context.contractKey())) {
            initialized.add(label);
            if (context.documentContains("/install")) {
                Node install = context.documentAt("/install");
                context.applyPatch(JsonPatch.replace("/children", install));
            }
            if (context.documentContains("/seed")) {
                int copies = context.documentContains("/seedCopies")
                        ? ((Number) context.documentAt("/seedCopies").getValue()).intValue() : 1;
                for (int copy = 0; copy < copies; copy++) {
                    context.emitEvent(token(((Number) context.documentAt("/seed").getValue()).intValue()));
                }
            }
        } else if ("react".equals(context.contractKey())) {
            int remaining = ((Number) context.occurrenceEvent().getNode("/remaining").getValue()).intValue();
            reactions.add(label + ":" + remaining);
            if (context.documentContains("/reactionInstall") && reactions.size() == 1) {
                context.applyPatch(JsonPatch.replace("/children", context.documentAt("/reactionInstall")));
            }
            if (removeMember != null && reactions.size() == 1) {
                context.applyPatch(JsonPatch.remove(removeMember));
                if (removeChannel) {
                    context.applyPatch(JsonPatch.remove("/contracts/react"));
                    context.applyPatch(JsonPatch.remove("/contracts/fromChildren"));
                }
            }
            context.applyPatch(JsonPatch.replace("/count", new Node().value(
                    ((Number) context.documentAt("/count").getValue()).longValue() + 1L)));
            if (reactions.size() == failAt) {
                throw new IllegalStateException("r2 failure at reaction " + failAt);
            }
            if (remaining > 0 || loop) {
                context.emitEvent(token(loop ? remaining : remaining - 1));
            }
        }
    }

    ClosureInvocationInput admission(AffectedClosureSnapshot snapshot, long gas) {
        return ClosureEvidenceFactory.admitClosure(snapshot,
                ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                        "r2-composition", null, null, "r2-admission-policy"), null,
                ClosureEvidenceFactory.executionPolicy(gas, Collections.emptyMap(), "r2-gas"), environment);
    }

    ClosureAttemptResult admit(ClosureInvocationInput input) {
        try (BlueClosureContracts contracts = new BlueClosureContracts(owner, value -> evidence = value)) {
            return contracts.admitClosureWithLifecycleQueue(input);
        }
    }

    ManagedOccurrenceBinding binding(DocumentId source, String path, DocumentId target,
                                     Node body, boolean active) {
        return ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), source,
                ScopeAddress.embedded(path, 1L), target, id(body), active, null);
    }

    static AffectedClosureSnapshot snapshot(Map<DocumentId, Node> bodies,
                                           List<ManagedOccurrenceBinding> bindings,
                                           DocumentId publicRoot) {
        Map<DocumentId, Long> generations = new LinkedHashMap<>();
        bodies.keySet().forEach(id -> generations.put(id, 1L));
        ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                new ComponentFinalizationInput(ManagedDocumentGraph.fromBindings(bodies.keySet(), bindings),
                        generations, bodies, bindings));
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (FinalizedDocumentEvidence exact : finalized.documents().values()) {
            documents.add(new ManagedDocumentSnapshot(exact.documentId(), exact.blueId(), exact.document(),
                    false, false, exact.documentId().equals(publicRoot), 0L, exact.componentGeneration()));
        }
        List<ComponentSnapshot> components = new ArrayList<>();
        finalized.components().forEach(component -> components.add(component.component()));
        return ClosureEvidenceFactory.affectedClosure(1L, documents, finalized.finalizedGraph().bindings(),
                components, Collections.singletonList(publicRoot));
    }

    static Node document(String label) {
        return new Node().properties("label", new Node().value(label))
                .properties("count", new Node().value(0L))
                .contracts(new Node().properties("lifecycle", typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))
                        .properties("init", handler("lifecycle").properties("event",
                                typed(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED))));
    }
    static Node handler(String channel) {
        return typed(HANDLER_ID).properties("channel", new Node().value(channel));
    }
    static Node embedded(String path) {
        Node result = typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
        return path == null ? result : result.properties("sourcePath", new Node().value(path));
    }
    static Node process(String key, String path) {
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties(key,
                new Node().items(Collections.singletonList(new Node().value(path))));
    }
    static Node token(int count) { return new Node().properties("remaining", new Node().value(count)); }
    static Node typed(String id) { return new Node().type(new Node().blueId(id)); }
    static String id(Node node) { return DirectBlueIdCalculator.calculateBlueId(node); }
    static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }
    static String hash(char c) { return "sha256:" + String.join("", Collections.nCopies(64, String.valueOf(c))); }
    static String diagnostic(ClosureProcessResult result) {
        return result.diagnostic() == null ? result.status().toString() : result.diagnostic().message();
    }
    static void rollback(ClosureInvocationInput input, ClosureProcessResult result) {
        assertFalse(result.commits());
        assertTrue(result.publicEvents().isEmpty());
        assertTrue(result.managedTransitionReceipts().isEmpty());
        assertNull(result.commitCompanion());
        for (ResultingDocument document : result.resultingDocuments()) {
            ManagedDocumentSnapshot before = input.snapshot().managedDocument(document.documentId());
            assertEquals(before.blueId(), document.afterBlueId());
            assertEquals(NodeWireForm.get(before.document()), NodeWireForm.get(document.document()));
        }
    }
    public static final class CampaignHandler extends HandlerContract { }
    public void close() { owner.close(); }
}
