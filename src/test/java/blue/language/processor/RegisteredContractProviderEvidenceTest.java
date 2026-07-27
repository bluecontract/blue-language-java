package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegisteredContractProviderEvidenceTest {

    @Test
    void exactCanonicalRegistrationMatchesFullProviderBackedRuntime() {
        TypeFixture fixture = new TypeFixture();
        Node suppliedCanonicalType = fixture.canonicalType.clone();
        DocumentProcessor standalone = DocumentProcessor.builder()
                .registerContractProcessor(
                        fixture.blueId, suppliedCanonicalType, new EvidenceChannelProcessor())
                .build();

        // Registration owns an immutable copy of the provider evidence.
        suppliedCanonicalType.name("mutated after registration");

        DocumentProcessingResult standaloneResult = standalone.initializeDocument(
                fixture.document());
        DocumentProcessingResult fullRuntimeResult;
        try (Blue fullRuntime = new Blue(fixture.provider)) {
            fullRuntime.registerContractProcessor(
                    fixture.blueId, new EvidenceChannelProcessor());
            fullRuntimeResult = fullRuntime.initializeDocument(fixture.document());
        }

        assertEquals(ProcessorStatus.SUCCESS, standaloneResult.status(),
                diagnosticMessage(standaloneResult));
        assertEquals(ProcessorStatus.SUCCESS, fullRuntimeResult.status(),
                diagnosticMessage(fullRuntimeResult));
        assertEquals(initializationDocumentId(fullRuntimeResult),
                initializationDocumentId(standaloneResult));
        assertNotEquals(EvidenceChannel.class.getSimpleName(),
                fixture.canonicalType.getName());
        assertNotNull(fixture.canonicalType.getDescription());
        assertEquals(RuntimeBlueIds.CHANNEL,
                fixture.canonicalType.getType().getBlueId());
        assertNotNull(fixture.canonicalType.getProperties().get("protocolVersion").getSchema());
    }

    @Test
    void runtimeExactCanonicalRegistrationInitializesStandaloneProcessor() {
        TypeFixture fixture = new TypeFixture();
        DocumentProcessor standalone = new DocumentProcessor();

        standalone.registerContractProcessor(
                fixture.blueId,
                fixture.canonicalType,
                new EvidenceChannelProcessor());
        DocumentProcessingResult result = standalone.initializeDocument(
                fixture.document());

        assertEquals(ProcessorStatus.SUCCESS, result.status(), diagnosticMessage(result));
        assertNotNull(initializationDocumentId(result));
    }

    @Test
    void registryBuilderEvidenceSeedsStandaloneProcessorTypeResolver() {
        TypeFixture fixture = new TypeFixture();
        EvidenceChannelProcessor registered = new EvidenceChannelProcessor();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(fixture.blueId, fixture.canonicalType, registered)
                .build();

        DocumentProcessor standalone = new DocumentProcessor(registry);
        DocumentProcessingResult result = standalone.initializeDocument(
                fixture.document());

        assertEquals(EvidenceChannel.class,
                standalone.getContractTypeResolver().resolveClass(fixture.blueId));
        assertEquals(ProcessorStatus.SUCCESS, result.status(), diagnosticMessage(result));
        assertSame(registered, registry.processors().get(fixture.blueId));
    }

    @Test
    void legacyExplicitBlueIdRegistrationDoesNotInventProviderContent() {
        TypeFixture fixture = new TypeFixture();
        DocumentProcessor standalone = DocumentProcessor.builder()
                .registerContractProcessor(fixture.blueId, new EvidenceChannelProcessor())
                .build();
        Node document = fixture.document();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> standalone.initializeDocument(document));

        assertEquals(
                BlueLanguageErrorCategory.ProviderUnavailable,
                BlueLanguageErrorClassifier.classify(failure));
        assertNull(document.getContracts().getProperties().get("initialized"));
        assertNull(document.getContracts().getProperties().get("terminated"));
    }

    @Test
    void activeScopePreflightDemandsLegacyExplicitProviderEvidence() {
        TypeFixture fixture = new TypeFixture();
        DocumentProcessor standalone = DocumentProcessor.builder()
                .registerContractProcessor(
                        fixture.blueId,
                        new EvidenceChannelProcessor())
                .build();
        ProcessorEngine.Execution execution =
                new ProcessorEngine.Execution(
                        standalone,
                        fixture.document());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> execution.preflightScope("/"));

        assertEquals(
                BlueLanguageErrorCategory.ProviderUnavailable,
                BlueLanguageErrorClassifier.classify(failure));
    }

    @Test
    void mismatchingCanonicalRegistrationIsRejectedAtomically() {
        TypeFixture fixture = new TypeFixture();
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        Node wrongContent = fixture.canonicalType.clone().description("different identity");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> registry.register(
                        fixture.blueId, wrongContent, new EvidenceChannelProcessor()));

        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertFalse(registry.processors().containsKey(fixture.blueId));
        assertNull(registry.canonicalTypeNode(fixture.blueId));
    }

    @Test
    void conflictingRuntimeTypeRegistrationLeavesRegistryAndResolverUnchanged() {
        TypeFixture fixture = new TypeFixture();
        EvidenceChannelProcessor original = new EvidenceChannelProcessor();
        DocumentProcessor standalone = DocumentProcessor.builder()
                .registerContractProcessor(
                        fixture.blueId, fixture.canonicalType, original)
                .build();
        ContractProcessorRegistry registry = standalone.getContractRegistry();
        long versionBefore = registry.version();
        String evidenceBefore = BlueIdCalculator.calculateBlueId(
                registry.canonicalTypeNode(fixture.blueId));

        assertThrows(IllegalStateException.class,
                () -> standalone.registerContractProcessor(
                        fixture.blueId,
                        fixture.canonicalType,
                        new ConflictingEvidenceChannelProcessor()));

        assertEquals(versionBefore, registry.version());
        assertSame(original, registry.processors().get(fixture.blueId));
        assertEquals(EvidenceChannel.class,
                standalone.getContractTypeResolver().resolveClass(fixture.blueId));
        assertEquals(evidenceBefore, BlueIdCalculator.calculateBlueId(
                registry.canonicalTypeNode(fixture.blueId)));
    }

    @Test
    void conflictingBuilderTypeRegistrationLeavesFirstRegistrationUsable() {
        TypeFixture fixture = new TypeFixture();
        EvidenceChannelProcessor original = new EvidenceChannelProcessor();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .registerContractProcessor(
                        fixture.blueId, fixture.canonicalType, original);

        assertThrows(IllegalStateException.class,
                () -> builder.registerContractProcessor(
                        fixture.blueId,
                        fixture.canonicalType,
                        new ConflictingEvidenceChannelProcessor()));

        DocumentProcessor standalone = builder.build();
        assertSame(original,
                standalone.getContractRegistry().processors().get(fixture.blueId));
        assertEquals(EvidenceChannel.class,
                standalone.getContractTypeResolver().resolveClass(fixture.blueId));
    }

    @Test
    void unsupportedProcessorRegistrationDoesNotPartiallyMutateRegistry() {
        TypeFixture fixture = new TypeFixture();
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        ContractProcessor<Contract> unsupported = () -> Contract.class;

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(
                        fixture.blueId, fixture.canonicalType, unsupported));

        assertEquals(0L, registry.version());
        assertFalse(registry.processors().containsKey(fixture.blueId));
        assertNull(registry.canonicalTypeNode(fixture.blueId));
    }

    private static String initializationDocumentId(DocumentProcessingResult result) {
        return result.document().getAsText("/contracts/initialized/documentId");
    }

    private static final class TypeFixture {
        private final Node canonicalType;
        private final BasicNodeProvider provider;
        private final String blueId;

        private TypeFixture() {
            Node authoredType = new Node()
                    .name("Protocol Evidence Channel")
                    .description("Identity-bearing provider description")
                    .type(new Node().blueId(RuntimeBlueIds.CHANNEL))
                    .properties("protocolVersion", new Node()
                            .value("v1")
                            .schema(new Schema().required(new Node().value(true))));
            provider = new BasicNodeProvider(authoredType);
            blueId = provider.getBlueIdByName(authoredType.getName());
            canonicalType = provider.fetchFirstByBlueId(blueId).clone().blueId(null);
        }

        private Node document() {
            return new Node()
                    .name("Provider Evidence Document")
                    .contracts(new Node().properties("incoming", new Node()
                            .type(new Node().blueId(blueId))));
        }
    }

    public static final class EvidenceChannel extends ChannelContract {
        private String protocolVersion;

        public String getProtocolVersion() {
            return protocolVersion;
        }

        public void setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
        }
    }

    public static final class ConflictingEvidenceChannel extends ChannelContract {
    }

    private static final class EvidenceChannelProcessor
            implements ChannelProcessor<EvidenceChannel> {

        @Override
        public Class<EvidenceChannel> contractType() {
            return EvidenceChannel.class;
        }

        @Override
        public boolean matches(EvidenceChannel contract, ChannelEvaluationContext context) {
            return false;
        }
    }

    private static final class ConflictingEvidenceChannelProcessor
            implements ChannelProcessor<ConflictingEvidenceChannel> {

        @Override
        public Class<ConflictingEvidenceChannel> contractType() {
            return ConflictingEvidenceChannel.class;
        }
    }
}
