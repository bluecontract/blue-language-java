package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Regression proofs for scope-local resolver/type-evidence ownership. */
final class ResolvedScopeViewEvidenceTest {

    private static final String CHANNEL_KEY = "incoming";

    @Test
    void shouldKeepStrictProjectionInlineTypeEvidenceLocal() {
        // given
        Node inlineType = new Node().name("Strict projected channel type");
        String inlineTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                inlineType);
        Node root = scopeWithContract(
                new Node()
                        .type(inlineType)
                        .properties("order", new Node().value(0)));
        VerifiedExecutionEvidence evidence = evidence(inlineTypeBlueId);

        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope languageScope =
                     language.processing().openScope(blueId -> null);
             DocumentProcessor processor = new DocumentProcessor();
             ProcessorInvocationServices services =
                     ProcessorInvocationServices.platform(
                             processor,
                             new LanguageProcessingSnapshotManager(
                                     languageScope),
                             languageScope.runtimeAccess(),
                             languageScope.newConformanceEngine())) {
            DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                    root,
                    null,
                    null,
                    services.snapshotManager(),
                    services.observer(),
                    new GasMeter(),
                    Collections.emptyMap(),
                    true);
            CanonicalTypeIdentityLookup containingEvidence =
                    runtime.snapshot().canonicalTypeIdentities();
            EvidenceClassificationView classification =
                    new EvidenceClassificationView(
                            services,
                            runtime,
                            root,
                            null,
                            () -> evidence);

            // when
            ResolvedScopeView projected = classification.scopeAt(
                    JsonPointer.ROOT);
            Node completedType = projected.resolved()
                    .getContracts()
                    .property(CHANNEL_KEY)
                    .getType()
                    .toNode();

            // then
            assertEquals(
                    inlineTypeBlueId,
                    projected.canonicalTypeIdentities()
                            .requireCanonicalTypeBlueId(completedType));
            assertNotSame(
                    containingEvidence,
                    projected.canonicalTypeIdentities(),
                    "projected nodes must retain the projection resolver's lookup");
        }
    }

    @Test
    void shouldReplaceIncompleteInputEvidenceWhenConfiguredReferenceResolvesFresh() {
        // given
        Node inlineType = new Node().name("Configured reference channel type");
        String inlineTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                inlineType);
        Node authoredRoot = scopeWithContract(
                new Node()
                        .type(inlineType)
                        .properties("order", new Node().value(0)));
        Node exactRoot;
        String exactRootBlueId;
        try (Blue canonicalizer = ProcessorTestSupport.blue()) {
            exactRoot = canonicalizer.canonicalize(authoredRoot);
            exactRootBlueId = canonicalizer.calculateSourceDocumentBlueId(
                    authoredRoot);
        }
        Node rootReference = new Node().blueId(exactRootBlueId);
        NodeProvider provider = blueId -> {
            if (exactRootBlueId.equals(blueId)) {
                return Collections.singletonList(exactRoot.clone());
            }
            if (inlineTypeBlueId.equals(blueId)) {
                return Collections.singletonList(inlineType.clone());
            }
            return null;
        };

        try (Blue blue = ProcessorTestSupport.blue(provider)) {
            DocumentProcessor processor = blue.getDocumentProcessor();
            ResolvedSnapshot admitted = ResolvedSnapshot.withDeferredSource(
                    FrozenNode.fromResolvedNode(rootReference),
                    FrozenNode.fromResolvedNode(exactRoot),
                    CanonicalTypeIdentityLookup.incomplete());
            DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                    admitted,
                    null,
                    processor.snapshotManager());
            EvidenceClassificationView classification =
                    new EvidenceClassificationView(
                            ProcessorInvocationServices.configured(processor),
                            runtime,
                            rootReference,
                            admitted,
                            () -> evidence(inlineTypeBlueId));

            // when
            ResolvedScopeView refreshed = classification.scopeAt(
                    JsonPointer.ROOT);
            Node completedType = refreshed.resolved()
                    .getContracts()
                    .property(CHANNEL_KEY)
                    .getType()
                    .toNode();

            // then
            assertFalse(admitted.canonicalTypeIdentities()
                    .findCanonicalTypeBlueId(completedType)
                    .isPresent());
            assertEquals(
                    inlineTypeBlueId,
                    refreshed.canonicalTypeIdentities()
                            .requireCanonicalTypeBlueId(completedType));
        }
    }

    @Test
    void shouldKeepInlineContractTypeEvidenceDuringRecognitionMaterialization() {
        // given
        Node authoredContract = new Node()
                .type(new Node().name("TestEventChannel"))
                .properties("order", new Node().value(0));
        Node exactContract;
        String contractBlueId;
        try (Blue canonicalizer = ProcessorTestSupport.blue()) {
            exactContract = canonicalizer.canonicalize(authoredContract);
            contractBlueId = canonicalizer.calculateSourceDocumentBlueId(
                    authoredContract);
        }
        NodeProvider provider = blueId -> contractBlueId.equals(blueId)
                ? Collections.singletonList(exactContract.clone())
                : null;
        Node selectedScope = scopeWithContract(
                new Node().blueId(contractBlueId));
        FrozenNode selected = FrozenNode.fromResolvedNode(selectedScope);

        try (Blue blue = ProcessorTestSupport.blue(provider)) {
            blue.registerContractProcessor(
                    new TestEventChannelProcessor());
            DocumentProcessor processor = blue.getDocumentProcessor();
            DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                    selectedScope,
                    null,
                    processor.snapshotManager());

            // when
            ResolvedScopeView recognition = runtime.contractRecognitionScope(
                    new ResolvedScopeView(
                            selected,
                            selected,
                            CanonicalTypeIdentityLookup.incomplete()));
            FrozenNode effectiveContract = recognition.resolved()
                    .getContracts()
                    .property(CHANNEL_KEY);
            String effectiveTypeBlueId = recognition
                    .canonicalTypeIdentities()
                    .requireCanonicalTypeBlueId(
                            effectiveContract.getType().toNode());
            ContractBundle bundle = processor.contractLoader().load(
                    selected,
                    recognition.resolved(),
                    JsonPointer.ROOT,
                    recognition.canonicalTypeIdentities());

            // then
            assertEquals(
                    ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL,
                    effectiveTypeBlueId);
            assertNotNull(bundle.channelBinding(CHANNEL_KEY));
        }
    }

    private static Node scopeWithContract(Node contract) {
        return new Node().contracts(new Node().properties(
                CHANNEL_KEY, contract));
    }

    private static VerifiedExecutionEvidence evidence(
            String effectiveTypeBlueId) {
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder(
                                JsonPointer.ROOT, CHANNEL_KEY)
                        .effectiveTypeBlueId(effectiveTypeBlueId)
                        .checkpointDomainBlueId("checkpoint-domain")
                        .checkpointSubjectBlueId("checkpoint-subject")
                        .build();
        return VerifiedExecutionEvidence.builder(
                        DirectBlueIdCalculator.calculateBlueId(
                                new Node().name("evidence root")),
                        DirectBlueIdCalculator.calculateBlueId(
                                new Node().name("evidence event")))
                .revisions(1L, 1L)
                .runtimeRegistryIdentity("registry")
                .eventOrderKey(ExternalOrderKey.of(
                        Collections.<Object>singletonList(1)))
                .delivery(delivery)
                .build();
    }
}
