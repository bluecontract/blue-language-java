package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.api.BlueCachePolicy;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.mapping.TypeClassResolver;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.BlueId;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CanonicalIdentityEvidenceTest {

    private static final String REGISTRATION_OPERATION_KEY =
            "operation/~identity";

    @Test
    void shouldTreatInlineAndReferencedExecutableTypesAsSameIdentity() {
        Node parentType = new Node()
                .name("Executable parent")
                .properties("inherited", new Node().value("fixed"));
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(
                parentType);
        Node childType = new Node()
                .name("Executable child")
                .type(reference(parentBlueId))
                .properties(
                        "inherited", new Node().value("fixed"),
                        "own", new Node().value("value"));
        NodeProvider provider = blueId -> parentBlueId.equals(blueId)
                ? Collections.singletonList(parentType.clone())
                : Collections.<Node>emptyList();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            String canonicalTypeBlueId =
                    CanonicalIdentityEvidence.sourceTypeBlueId(
                            childType.clone(),
                            manager,
                            "test executable type");
            assertNotEquals(
                    DirectBlueIdCalculator.calculateBlueId(childType),
                    canonicalTypeBlueId,
                    "the fixture must exercise canonical reconstruction");

            Map<String, List<String>> executableFields =
                    Collections.singletonMap(
                            canonicalTypeBlueId,
                            Collections.singletonList("body"));
            Set<String> inlinePaths =
                    ExecutableBodyPathCatalog.fromNodeDirectContracts(
                            documentWithContractType(childType),
                            Collections.singleton("/"),
                            executableFields,
                            manager);
            Set<String> referencePaths =
                    ExecutableBodyPathCatalog.fromNodeDirectContracts(
                            documentWithContractType(
                                    reference(canonicalTypeBlueId)),
                            Collections.singleton("/"),
                            executableFields,
                            manager);

            assertEquals(referencePaths, inlinePaths);
            assertEquals(
                    Collections.singleton("/contracts/channel/body"),
                    inlinePaths);
        }
    }

    @Test
    void shouldRequireProducingSnapshotEvidenceForCompletedType() {
        Node parentType = new Node()
                .name("Evidence parent")
                .properties("inherited", new Node().value("fixed"));
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(
                parentType);
        Node childType = new Node()
                .name("Evidence child")
                .type(reference(parentBlueId))
                .properties("inherited", new Node().value("fixed"));
        NodeProvider provider = blueId -> parentBlueId.equals(blueId)
                ? Collections.singletonList(parentType.clone())
                : Collections.<Node>emptyList();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            String expected = manager.fromDocumentTransient(
                    childType.clone()).blueId();
            ResolvedSnapshot containing = manager.fromDocumentTransient(
                    new Node().type(childType.clone()));
            Node completedType = containing.resolvedRoot().getType();

            assertEquals(
                    expected,
                    CanonicalIdentityEvidence.resolvedTypeBlueId(
                            completedType,
                            containing.canonicalTypeIdentities(),
                            "test completed type"));
            assertThrows(
                    IllegalStateException.class,
                    () -> CanonicalIdentityEvidence.resolvedTypeBlueId(
                            completedType,
                            CanonicalTypeIdentityLookup.incomplete(),
                            "test completed type"));
        }
    }

    @Test
    void shouldUseAuthoredInlineTypeCanonicalIdentityInDirectPreflight() {
        Node parentType = new Node()
                .name("Preflight parent")
                .properties("inherited", new Node().value("fixed"));
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(
                parentType);
        Node inlineContractType = new Node()
                .name("Preflight contract")
                .type(reference(parentBlueId))
                .properties(
                        "inherited", new Node().value("fixed"),
                        "own", new Node().value("value"));
        NodeProvider provider = blueId -> parentBlueId.equals(blueId)
                ? Collections.singletonList(parentType.clone())
                : Collections.<Node>emptyList();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            String canonicalTypeBlueId = manager.fromDocumentTransient(
                    inlineContractType.clone()).blueId();
            assertNotEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineContractType),
                    canonicalTypeBlueId,
                    "the fixture must exercise inline-type minimization");

            TypeClassResolver typeResolver = new TypeClassResolver()
                    .register(canonicalTypeBlueId, Contract.class);
            ContractLoader loader = new ContractLoader(
                    ContractProcessorRegistryBuilder.create().build(),
                    new NodeToObjectConverter(typeResolver),
                    typeResolver,
                    BlueCachePolicy.boundedDefaults(),
                    provider,
                    false,
                    manager);
            FrozenNode selected = FrozenNode.fromSourceNode(
                    documentWithContractType(inlineContractType));

            assertDoesNotThrow(
                    () -> loader.preflightSelectedContractHeaders(selected));
        }
    }

    @Test
    void shouldUseProducingInlineTypeEvidenceForHostedHandlerContract() {
        Node inlineSubjectType = new Node()
                .name("Registration subject");
        Node channelType = new Node().name(
                "Registration evidence channel");
        Node operationType = new Node().name(
                "Registration evidence operation");
        Node handlerType = new Node().name(
                "Registration evidence handler");
        Node authoredSubject = new Node()
                .type(inlineSubjectType.clone())
                .properties(
                        "payload",
                        new Node().value("stable"));
        String channelTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(channelType);
        String operationTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(operationType);
        String handlerTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(handlerType);
        NodeProvider provider = blueId -> {
            if (channelTypeBlueId.equals(blueId)) {
                return Collections.singletonList(channelType.clone());
            }
            if (operationTypeBlueId.equals(blueId)) {
                return Collections.singletonList(operationType.clone());
            }
            if (handlerTypeBlueId.equals(blueId)) {
                return Collections.singletonList(handlerType.clone());
            }
            return Collections.emptyList();
        };

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            ResolvedSnapshot subjectSnapshot =
                    manager.fromDocumentTransient(
                            authoredSubject.clone());
            Node materializedSubject = subjectSnapshot.resolvedRoot();
            assertFalse(materializedSubject.getType().isReferenceOnly(),
                    "the fixture must carry materialized inline type metadata");

            ResolvedSnapshot registrationSnapshot =
                    manager.fromDocumentTransient(
                            registrationScope(
                                    authoredSubject,
                                    channelTypeBlueId,
                                    operationTypeBlueId,
                                    handlerTypeBlueId));
            ContractLoader loader = registrationLoader(
                    provider,
                    channelType,
                    operationType,
                    handlerType,
                    channelTypeBlueId,
                    operationTypeBlueId,
                    handlerTypeBlueId,
                    manager);

            RegistrationHandlerProcessor.reset();
            loader.load(registrationSnapshot, "/");

            assertEquals(1, RegistrationHandlerProcessor.attempts);
            assertEquals(
                    registrationSnapshot.canonicalBlueIdAt(
                            JsonPointer.append(
                                    JsonPointer.append(
                                            JsonPointer.append(
                                                    JsonPointer.ROOT,
                                                    "contracts"),
                                            REGISTRATION_OPERATION_KEY),
                                    "subject")),
                    RegistrationHandlerProcessor.subjectBlueId);
            assertEquals(
                    subjectSnapshot.blueId(),
                    RegistrationHandlerProcessor.subjectBlueId);

            RegistrationHandlerProcessor.reset();
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> loader.load(
                            registrationSnapshot.sourceAt(JsonPointer.ROOT),
                            registrationSnapshot.resolvedAt(JsonPointer.ROOT),
                            "/",
                            registrationSnapshot
                                    .canonicalTypeIdentities()));

            assertEquals(1, RegistrationHandlerProcessor.attempts,
                    "the detached lane must retain valid Source/effective "
                            + "inputs and fail specifically in hosted contractAs");
            Throwable root = rootCause(failure);
            assertEquals(
                    IllegalStateException.class,
                    root.getClass(),
                    root.toString());
            assertTrue(root.getMessage().contains(
                    "authoritative Canonical Identity Input"));
        }
    }

    @Test
    void shouldKeepExactNodeHeadersOpaqueToSnapshotIdentityMapping() {
        Node contractType = new Node().name(
                "Snapshot projection contract");
        Node opaqueType = new Node()
                .name("Opaque header type")
                .properties(
                        "inherited",
                        new Node().value("materialized only when resolved"));
        String contractTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(contractType);
        String opaqueTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(opaqueType);
        NodeProvider provider = blueId -> {
            if (contractTypeBlueId.equals(blueId)) {
                return Collections.singletonList(contractType.clone());
            }
            if (opaqueTypeBlueId.equals(blueId)) {
                return Collections.singletonList(opaqueType.clone());
            }
            return Collections.emptyList();
        };
        Node source = new Node().contracts(new Node().properties(
                "operation",
                new Node()
                        .type(reference(contractTypeBlueId))
                        .properties(
                                "subject",
                                new Node()
                                        .type(new Node().name(
                                                "Inline projection subject"))
                                        .properties(
                                                "payload",
                                                new Node().value("stable")),
                                "deferred",
                                new Node().value("cold"),
                                "opaque",
                                new Node()
                                        .type(reference(opaqueTypeBlueId))
                                        .properties(
                                                "payload",
                                                new Node().value("exact")))));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            ResolvedSnapshot snapshot = manager.fromDocumentTransient(source);
            String contractPointer = "/contracts/operation";
            Node exactHeader = snapshot.sourceAt(contractPointer).toNode();
            exactHeader.getProperties().remove("deferred");
            Node exactOpaque = exactHeader.getProperties().get("opaque");
            assertTrue(exactOpaque.getType().isReferenceOnly());
            assertFalse(snapshot.resolvedNodeAt(contractPointer)
                    .getProperties().get("opaque")
                    .getType().isReferenceOnly());
            TypeClassResolver resolver = new TypeClassResolver()
                    .register(
                            contractTypeBlueId,
                            SnapshotProjectionContract.class);
            ContractHeaderMappingEvidence evidence =
                    ContractHeaderMappingEvidence.fromSnapshot(snapshot, "/");

            SnapshotProjectionContract converted =
                    evidence.convertContract(
                            "operation",
                            exactHeader,
                            Collections.singleton("deferred"),
                            SnapshotProjectionContract.class,
                            true,
                            new NodeToObjectConverter(resolver),
                            snapshot.canonicalTypeIdentities());

            assertEquals(
                    snapshot.canonicalBlueIdAt(
                            contractPointer + "/subject"),
                    converted.subjectBlueId());
            assertNull(converted.deferred());
            assertTrue(converted.opaque().getType().isReferenceOnly());
            assertEquals(
                    opaqueTypeBlueId,
                    converted.opaque().getType().getBlueId());
            assertNotSame(exactOpaque, converted.opaque());
        }
    }

    private static ContractLoader registrationLoader(
            NodeProvider provider,
            Node channelType,
            Node operationType,
            Node handlerType,
            String channelTypeBlueId,
            String operationTypeBlueId,
            String handlerTypeBlueId,
            ProcessingSnapshotManager snapshotManager) {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                channelTypeBlueId,
                                channelType,
                                new RegistrationChannelProcessor())
                        .register(
                                operationTypeBlueId,
                                operationType,
                                new RegistrationOperationProcessor())
                        .register(
                                handlerTypeBlueId,
                                handlerType,
                                new RegistrationHandlerProcessor())
                        .build();
        TypeClassResolver resolver = new TypeClassResolver()
                .register(channelTypeBlueId, RegistrationChannel.class)
                .register(operationTypeBlueId, RegistrationOperation.class)
                .register(handlerTypeBlueId, RegistrationHandler.class);
        return new ContractLoader(
                registry,
                new NodeToObjectConverter(resolver),
                resolver,
                BlueCachePolicy.boundedDefaults(),
                provider,
                false,
                snapshotManager);
    }

    private static Node registrationScope(
            Node subject,
            String channelTypeBlueId,
            String operationTypeBlueId,
            String handlerTypeBlueId) {
        return new Node().contracts(new Node().properties(
                "handler",
                new Node()
                        .type(reference(handlerTypeBlueId))
                        .properties(
                                "operation",
                                new Node().value(
                                        REGISTRATION_OPERATION_KEY)),
                REGISTRATION_OPERATION_KEY,
                new Node()
                        .type(reference(operationTypeBlueId))
                        .properties("subject", subject.clone()),
                "channel",
                new Node().type(reference(channelTypeBlueId))));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static final class RegistrationChannel extends ChannelContract {
    }

    public static final class RegistrationOperation extends MarkerContract {
        @BlueId
        private String subject;

        String subjectBlueId() {
            return subject;
        }
    }

    public static final class RegistrationHandler extends HandlerContract {
        private String operation;

        String operation() {
            return operation;
        }
    }

    public static final class SnapshotProjectionContract
            extends MarkerContract {
        @BlueId
        private String subject;
        private String deferred;
        private Node opaque;

        String subjectBlueId() {
            return subject;
        }

        String deferred() {
            return deferred;
        }

        Node opaque() {
            return opaque;
        }
    }

    private static final class RegistrationChannelProcessor
            implements ChannelProcessor<RegistrationChannel> {

        @Override
        public Class<RegistrationChannel> contractType() {
            return RegistrationChannel.class;
        }
    }

    private static final class RegistrationOperationProcessor
            implements ContractProcessor<RegistrationOperation> {

        @Override
        public Class<RegistrationOperation> contractType() {
            return RegistrationOperation.class;
        }
    }

    private static final class RegistrationHandlerProcessor
            implements HandlerProcessor<RegistrationHandler> {

        private static int attempts;
        private static String subjectBlueId;

        private static void reset() {
            attempts = 0;
            subjectBlueId = null;
        }

        @Override
        public Class<RegistrationHandler> contractType() {
            return RegistrationHandler.class;
        }

        @Override
        public String deriveChannel(
                RegistrationHandler handler,
                HandlerRegistrationContext context) {
            attempts++;
            RegistrationOperation operation = context.contractAs(
                    handler.operation(), RegistrationOperation.class);
            subjectBlueId = operation.subjectBlueId();
            return "channel";
        }

        @Override
        public void execute(
                RegistrationHandler handler,
                ProcessorExecutionContext context) {
            // Registration-only probe.
        }
    }

    private static Node documentWithContractType(Node type) {
        return new Node().contracts(
                new Node().properties(
                        "channel",
                        new Node()
                                .type(type.clone())
                                .properties(
                                        "body",
                                        new Node().value("cold"))));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
