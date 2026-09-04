package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CanonicalTypeIdentityLookup;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static blue.language.processor.DocumentProcessingResultTestSupport.diagnosticMessage;
import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class EffectiveContractRefreshAndReferenceResultTest {

    private static final String TEST_EVENT_CHANNEL_TYPE =
            ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;
    private static final String TEST_EVENT_TYPE =
            ProcessorTestTypeBlueIds.TEST_EVENT;
    private static final String SET_PROPERTY_TYPE =
            ProcessorTestTypeBlueIds.SET_PROPERTY;

    @Test
    void shouldRetainInheritedChannelAndHandlerTypesAcrossInitializationRefresh() {
        // given
        Fixture fixture = fixture();
        Node document = fixture.document();

        // when
        DocumentProcessingResult first =
                fixture.blue.processDocument(
                        document,
                        event("first"));
        DocumentProcessingResult second =
                fixture.blue.processDocument(
                        first.document(),
                        event("second"));

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                first.status(),
                diagnosticMessage(first));
        assertEquals(
                new BigInteger("2"),
                first.document().get("/state"));
        assertNotNull(
                first.document().getAsNode(
                        "/contracts/initialized"));
        assertEquals(
                ProcessorStatus.SUCCESS,
                second.status(),
                diagnosticMessage(second));
        assertEquals(
                new BigInteger("2"),
                second.document().get("/state"));
    }

    @Test
    void shouldReturnPublishedCanonicalRootAfterPureReferenceProcessing() {
        // given
        Fixture fixture = fixture();
        DocumentProcessingResult initialized =
                fixture.blue.processDocument(
                        fixture.directDocument(),
                        event("initial"));
        fixture.provider.addSingleNodes(
                initialized.document());
        String initializedBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        initialized.document());
        Node pureReference =
                new Node().blueId(initializedBlueId);

        // when
        DocumentProcessingResult result =
                fixture.blue.processDocument(
                        pureReference,
                        event("from-reference"));

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
        assertFalse(
                result.document().isReferenceOnly(),
                "a successful transition must publish the resulting canonical Root");
        assertEquals(
                new BigInteger("2"),
                result.document().get("/state"));
        assertNotNull(
                result.document().getAsNode(
                        "/contracts/checkpoint"));
    }

    @Test
    void shouldAllowTypelessDirectOverlayWhenEffectiveContractHasAType() {
        // given
        ContractLoader loader =
                DocumentProcessor.builder()
                        .build()
                        .contractLoader();
        FrozenNode selected =
                scopeWithContract(
                        "lifecycle",
                        new Node().properties(
                                "order",
                                new Node().value(1)));
        FrozenNode effective =
                scopeWithContract(
                        "lifecycle",
                        new Node()
                                .type(reference(
                                        RuntimeBlueIds
                                                .LIFECYCLE_EVENT_CHANNEL))
                                .properties(
                                        "order",
                                        new Node().value(1)));

        // when
        loader.preflightSelectedContractHeaders(
                selected);
        ContractBundle bundle =
                loader.load(
                        selected,
                        effective,
                        "/",
                        CanonicalTypeIdentityLookup.incomplete());

        // then
        assertNotNull(
                bundle.channelBinding(
                        "lifecycle"));
        assertEquals(
                RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL,
                bundle.effectiveContractSnapshot(
                        "lifecycle")
                        .effectiveTypeBlueId());
    }

    @Test
    void shouldRefreshReferenceBackedOverlayFromEffectiveScopeType() {
        // given
        Node scopeType =
                new Node().name(
                        "Refresh Scope Type");
        String scopeTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        scopeType);
        Node typelessOverlay =
                new Node().properties(
                        "order",
                        new Node().value(7));
        String overlayBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        typelessOverlay);
        Node selected =
                new Node()
                        .type(reference(
                                scopeTypeBlueId))
                        .contracts(
                                new Node().properties(
                                        "lifecycle",
                                        reference(
                                                overlayBlueId)));
        FrozenNode selectedScope =
                FrozenNode.fromNode(
                        selected);
        FrozenNode unresolvedEffectiveScope =
                FrozenNode.fromResolvedNode(
                        selected);
        RefreshingSnapshotManager manager =
                new RefreshingSnapshotManager(
                        scopeTypeBlueId,
                        scopeType,
                        overlayBlueId,
                        typelessOverlay);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        Nodes.emptyObject(),
                        null,
                        manager);

        // when
        ResolvedScopeView refreshedView =
                runtime.contractRecognitionScope(
                        new ResolvedScopeView(
                                selectedScope,
                                unresolvedEffectiveScope,
                                CanonicalTypeIdentityLookup.incomplete()));
        FrozenNode refreshed = refreshedView.resolved();
        FrozenNode lifecycle =
                refreshed.getContracts()
                        .property(
                                "lifecycle");

        // then
        assertEquals(
                RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL,
                lifecycle.getType()
                        .getReferenceBlueId());
        assertEquals(
                new BigInteger("7"),
                lifecycle.property(
                        "order")
                        .getValue());
        assertEquals(
                Arrays.asList(
                        overlayBlueId,
                        scopeTypeBlueId),
                manager.exactMaterializationBlueIds);
        assertEquals(
                1,
                manager.refreshInputs.size());
        FrozenNode refreshedOverlay =
                manager.refreshInputs.get(0)
                        .getContracts()
                        .property("lifecycle");
        assertFalse(refreshedOverlay.isReferenceOnly());
        assertEquals(
                overlayBlueId,
                refreshedOverlay.blueId());
    }

    @Test
    void shouldRejectTypelessContractWithoutAnEffectiveType() {
        // given
        ContractLoader loader =
                DocumentProcessor.builder()
                        .build()
                        .contractLoader();
        FrozenNode typeless =
                scopeWithContract(
                        "missingType",
                        new Node().properties(
                                "order",
                                new Node().value(1)));

        // when
        Throwable failure =
                captureFailure(
                        () -> loader.load(
                                typeless,
                                typeless,
                                "/",
                                CanonicalTypeIdentityLookup.incomplete()));

        // then
        assertInstanceOf(
                MustUnderstandFailureException.class,
                failure);
        assertEquals(
                ProcessorErrorCategory.UnsupportedRuntimeType,
                ((MustUnderstandFailureException) failure)
                        .errorCategory());
    }

    @Test
    void shouldRejectExplicitUnknownContractTypeDuringPreflight() {
        // given
        ContractLoader loader =
                DocumentProcessor.builder()
                        .build()
                        .contractLoader();
        FrozenNode selected =
                scopeWithContract(
                        "unknown",
                        new Node().type(
                                reference(
                                        "unknown-contract-type")));

        // when
        Throwable failure =
                captureFailure(
                        () -> loader
                                .preflightSelectedContractHeaders(
                                        selected));

        // then
        assertInstanceOf(
                MustUnderstandFailureException.class,
                failure);
        assertEquals(
                ProcessorErrorCategory.UnsupportedRuntimeType,
                ((MustUnderstandFailureException) failure)
                        .errorCategory());
    }

    private static Fixture fixture() {
        Node rootType =
                new Node()
                        .name(
                                "Effective Contract Refresh Root")
                        .contracts(
                                new Node()
                                        .properties(
                                                "lifecycle",
                                                new Node()
                                                        .type(reference(
                                                                RuntimeBlueIds
                                                                        .LIFECYCLE_EVENT_CHANNEL)))
                                        .properties(
                                                "initializeState",
                                                new Node()
                                                        .type(reference(
                                                                SET_PROPERTY_TYPE))
                                                        .properties(
                                                                "channel",
                                                                new Node().value(
                                                                        "lifecycle"))
                                                        .properties(
                                                                "propertyKey",
                                                                new Node().value(
                                                                        "/state")))
                                        .properties(
                                                "eventState",
                                                new Node()
                                                        .type(reference(
                                                                SET_PROPERTY_TYPE))
                                                        .properties(
                                                                "channel",
                                                                new Node().value(
                                                                        "events"))
                                                        .properties(
                                                                "propertyKey",
                                                                new Node().value(
                                                                        "/state"))));
        BasicNodeProvider provider =
                new BasicNodeProvider(rootType);
        String rootTypeBlueId =
                provider.getBlueIdByName(
                        rootType.getName());
        Blue blue =
                ProcessorTestSupport.blue(
                        provider);
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(
                new SetPropertyContractProcessor());
        DocumentProcessorExactFeederSupport.install(
                blue);
        return new Fixture(
                blue,
                provider,
                rootTypeBlueId);
    }

    private static FrozenNode scopeWithContract(
            String key,
            Node contract) {
        return FrozenNode.fromResolvedNode(
                new Node().contracts(
                        new Node().properties(
                                key,
                                contract)));
    }

    private static Node event(String id) {
        return new Node()
                .type(reference(
                        TEST_EVENT_TYPE))
                .properties(
                        "eventId",
                        new Node().value(id));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture {
        private final Blue blue;
        private final BasicNodeProvider provider;
        private final String rootTypeBlueId;

        private Fixture(
                Blue blue,
                BasicNodeProvider provider,
                String rootTypeBlueId) {
            this.blue = blue;
            this.provider = provider;
            this.rootTypeBlueId =
                    rootTypeBlueId;
        }

        private Node document() {
            return new Node()
                    .name(
                            "Effective Contract Refresh Instance")
                    .type(reference(
                            rootTypeBlueId))
                    .properties(
                            "state",
                            new Node().value(0))
                    .contracts(
                            new Node()
                                    .properties(
                                            "lifecycle",
                                            new Node().properties(
                                                    "order",
                                                    new Node().value(
                                                            0)))
                                    .properties(
                                            "initializeState",
                                            new Node().properties(
                                                    "propertyValue",
                                                    new Node().value(
                                                            1)))
                                    .properties(
                                            "events",
                                            new Node()
                                                    .type(reference(
                                                            TEST_EVENT_CHANNEL_TYPE))
                                                    .properties(
                                                            "eventType",
                                                            new Node().value(
                                                                    TEST_EVENT_TYPE)))
                                    .properties(
                                            "eventState",
                                            new Node().properties(
                                                    "propertyValue",
                                                    new Node().value(
                                                            2))));
        }

        private Node directDocument() {
            Node document =
                    document();
            document.type((Node) null);
            document.getContracts()
                    .getProperties()
                    .get("lifecycle")
                    .type(reference(
                            RuntimeBlueIds
                                    .LIFECYCLE_EVENT_CHANNEL));
            document.getContracts()
                    .getProperties()
                    .get("initializeState")
                    .type(reference(
                            SET_PROPERTY_TYPE))
                    .properties(
                            "channel",
                            new Node().value(
                                    "lifecycle"))
                    .properties(
                            "propertyKey",
                            new Node().value(
                                    "/state"));
            document.getContracts()
                    .getProperties()
                    .get("eventState")
                    .type(reference(
                            SET_PROPERTY_TYPE))
                    .properties(
                            "channel",
                            new Node().value(
                                    "events"))
                    .properties(
                            "propertyKey",
                            new Node().value(
                                    "/state"));
            return document;
        }
    }

    private static final class RefreshingSnapshotManager
            implements ProcessingSnapshotManager {
        private final String scopeTypeBlueId;
        private final FrozenNode scopeType;
        private final String overlayBlueId;
        private final FrozenNode typelessOverlay;
        private final List<String> exactMaterializationBlueIds =
                new ArrayList<>();
        private final List<FrozenNode> refreshInputs =
                new ArrayList<>();

        private RefreshingSnapshotManager(
                String scopeTypeBlueId,
                Node scopeType,
                String overlayBlueId,
                Node typelessOverlay) {
            this.scopeTypeBlueId =
                    scopeTypeBlueId;
            this.scopeType =
                    FrozenNode.fromNode(
                            scopeType);
            this.overlayBlueId =
                    overlayBlueId;
            this.typelessOverlay =
                    FrozenNode.fromResolvedNode(
                            typelessOverlay);
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            refreshInputs.add(
                    FrozenNode.fromNode(
                            document));
            Node effective =
                    document.clone();
            effective.getContracts()
                    .properties(
                            "lifecycle",
                            new Node()
                                    .type(reference(
                                            RuntimeBlueIds
                                                    .LIFECYCLE_EVENT_CHANNEL))
                                    .properties(
                                            "order",
                                            new Node().value(
                                                    7)));
            FrozenNode canonical =
                    FrozenNode.fromNode(
                            document);
            return new ResolvedSnapshot(
                    canonical,
                    FrozenNode.fromResolvedNode(
                            effective),
                    canonical.blueId());
        }

        @Override
        public FrozenNode materializeVerifiedReference(
                FrozenNode reference) {
            String blueId =
                    reference.getReferenceBlueId();
            exactMaterializationBlueIds.add(
                    blueId);
            if (overlayBlueId.equals(blueId)) {
                return typelessOverlay;
            }
            if (scopeTypeBlueId.equals(blueId)) {
                return scopeType;
            }
            throw new AssertionError(
                    "Unexpected exact materialization: " + blueId);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return snapshot;
        }
    }
}
