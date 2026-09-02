package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.Blue;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.provider.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalMergingProcessorCapability;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.JsonPatch;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

class PatchImpactIncrementalResolutionTest {

    @Test
    void shouldVerifyDependencyFreeTypedScalarReplacementMatchesFullOracleAfterEveryPatch() {
        // given
        Fixture fixture = Fixture.withUnrelatedTypeContribution();
        ResolvedSnapshot base = fixture.snapshot();
        FrozenNode unaffected = base.resolvedAt("/inheritedUnrelated");
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();

        DocumentProcessor processor = fixture.blue.getDocumentProcessor();
        DocumentProcessingRuntime incremental = new DocumentProcessingRuntime(
                base,
                processor.conformanceEngine(),
                processor.snapshotManager(),
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(fixture.blue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                oracleManager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/status", new Node().value("confirmed")),
                JsonPatch.replace("/status", new Node().value("fulfilled")),
                JsonPatch.replace("/status", new Node().value("settled")));

        // when
        List<PatchObservation> observations = new ArrayList<>();
        for (JsonPatch patch : patches) {
            DocumentUpdateData incrementalUpdate =
                    incremental.applyPatch("/", patch);
            DocumentUpdateData oracleUpdate =
                    oracle.applyPatch("/", patch);
            observations.add(new PatchObservation(
                    oracle.snapshot(),
                    incremental.snapshot(),
                    oracleUpdate,
                    incrementalUpdate,
                    null,
                    incremental.snapshot()
                            .resolvedAt("/inheritedUnrelated")));
        }
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        FrozenNode finalUnaffected =
                incremental.snapshot()
                        .resolvedAt("/inheritedUnrelated");

        // then
        for (PatchObservation observation : observations) {
            assertSnapshotEquals(fixture.blue,
                    observation.expectedSnapshot,
                    observation.actualSnapshot);
            assertEquals(fixture.blue.nodeToJson(
                            observation.expectedUpdate.before()),
                    fixture.blue.nodeToJson(
                            observation.actualUpdate.before()));
            assertEquals(fixture.blue.nodeToJson(
                            observation.expectedUpdate.after()),
                    fixture.blue.nodeToJson(
                            observation.actualUpdate.after()));
            assertEquals(observation.expectedUpdate.path(),
                    observation.actualUpdate.path());
            assertEquals(observation.expectedUpdate.op(),
                    observation.actualUpdate.op());
        }
        assertEquals(3L, snapshot.counter("patchImpactAnalyses"));
        assertEquals(3L, snapshot.counter("patchImpactValueOnly"), snapshot.toString());
        assertEquals(3L, snapshot.counter("incrementalSnapshotResolutions"));
        assertEquals(0L, snapshot.counter("fullSnapshotFallbacks"));
        assertEquals(0L, snapshot.counter("fullCanonicalRootMaterializations"));
        assertEquals(0L, snapshot.counter("fullResolvedRootMaterializations"));
        assertEquals(0L, snapshot.counter("conformancePlans"));
        assertEquals(3, oracleManager.fullResolutions);
        assertSame(unaffected, finalUnaffected,
                "the incremental splice must retain an unrelated resolved subtree by identity");
    }

    @Test
    void shouldVerifyBasicTypedLeafReplacementPreservesResolvedMetadataAndMatchesFullOracleAfterEveryPatch() {
        // given
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshot();
        FrozenNode unaffected = base.resolvedAt("/inheritedUnrelated");
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        FullOracleSnapshotManager incrementalManager = new FullOracleSnapshotManager(fixture.blue, true);
        DocumentProcessingRuntime incremental = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                incrementalManager,
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(fixture.blue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                oracleManager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/status", new Node().value("confirmed")),
                JsonPatch.replace("/status", new Node().value("fulfilled")),
                JsonPatch.replace("/status", new Node().value("settled")));

        // when
        List<PatchObservation> observations = new ArrayList<>();
        for (JsonPatch patch : patches) {
            DocumentUpdateData incrementalUpdate =
                    incremental.applyPatch("/", patch);
            DocumentUpdateData oracleUpdate =
                    oracle.applyPatch("/", patch);
            FrozenNode resolvedStatus = incremental.snapshot().resolvedAt("/status");
            observations.add(new PatchObservation(
                    oracle.snapshot(),
                    incremental.snapshot(),
                    oracleUpdate,
                    incrementalUpdate,
                    resolvedStatus,
                    incremental.snapshot()
                            .resolvedAt("/inheritedUnrelated")));
        }
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();

        // then
        for (PatchObservation observation : observations) {
            assertSnapshotEquals(fixture.blue,
                    observation.expectedSnapshot,
                    observation.actualSnapshot);
            assertEquals(fixture.blue.nodeToJson(
                            observation.expectedUpdate.before()),
                    fixture.blue.nodeToJson(
                            observation.actualUpdate.before()));
            assertEquals(fixture.blue.nodeToJson(
                            observation.expectedUpdate.after()),
                    fixture.blue.nodeToJson(
                            observation.actualUpdate.after()));
            assertEquals(TEXT_TYPE_BLUE_ID,
                    observation.resolvedChangedNode
                            .getType().getReferenceBlueId());
            assertSame(unaffected,
                    observation.unaffectedNode);
        }
        assertEquals(3L, snapshot.counter("patchImpactValueOnly"), snapshot.toString());
        assertEquals(3L, snapshot.counter("incrementalSnapshotResolutions"));
        assertEquals(0L, snapshot.counter("fullSnapshotFallbacks"));
        assertEquals(0L, snapshot.counter("fullCanonicalRootMaterializations"));
        assertEquals(0L, snapshot.counter("fullResolvedRootMaterializations"));
        assertEquals(0L, snapshot.counter("conformancePlans"));
        assertEquals(0, incrementalManager.fullResolutions);
        assertEquals(3, oracleManager.fullResolutions);
    }

    @Test
    void shouldVerifyNonEmptyProcessorContractsRemainSharedAcrossTypedLeafFastPathPatches() {
        // given
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshotWithNonEmptyContracts();
        FrozenNode unaffectedContract = base.resolvedAt("/contracts/retained");
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        FullOracleSnapshotManager incrementalManager = new FullOracleSnapshotManager(fixture.blue, true);
        DocumentProcessingRuntime incremental = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                incrementalManager,
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(fixture.blue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                oracleManager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/status", new Node().value("confirmed")),
                JsonPatch.replace("/status", new Node().value("fulfilled")),
                JsonPatch.replace("/status", new Node().value("settled")));

        // when
        List<PatchObservation> observations = new ArrayList<>();
        for (JsonPatch patch : patches) {
            DocumentUpdateData incrementalUpdate =
                    incremental.applyPatch("/", patch);
            DocumentUpdateData oracleUpdate =
                    oracle.applyPatch("/", patch);
            observations.add(new PatchObservation(
                    oracle.snapshot(),
                    incremental.snapshot(),
                    oracleUpdate,
                    incrementalUpdate,
                    null,
                    incremental.snapshot()
                            .resolvedAt("/contracts/retained")));
        }
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();

        // then
        for (PatchObservation observation : observations) {
            assertSnapshotEquals(fixture.blue,
                    observation.expectedSnapshot,
                    observation.actualSnapshot);
            assertEquals(fixture.blue.nodeToJson(
                            observation.expectedUpdate.before()),
                    fixture.blue.nodeToJson(
                            observation.actualUpdate.before()));
            assertEquals(fixture.blue.nodeToJson(
                            observation.expectedUpdate.after()),
                    fixture.blue.nodeToJson(
                            observation.actualUpdate.after()));
            assertSame(unaffectedContract,
                    observation.unaffectedNode);
        }
        assertEquals(3L, snapshot.counter("incrementalSnapshotResolutions"));
        assertEquals(0L, snapshot.counter("fullSnapshotFallbacks"));
        assertEquals(0L, snapshot.counter("fullCanonicalRootMaterializations"));
        assertEquals(0L, snapshot.counter("fullResolvedRootMaterializations"));
        assertEquals(0, incrementalManager.fullResolutions);
        assertEquals(3, oracleManager.fullResolutions);
    }

    @Test
    void shouldVerifyPatchUnderContractsUsesNamedFullFallbackAndMatchesOracle() {
        // given
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshotWithNonEmptyContracts();
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        FullOracleSnapshotManager incrementalManager = new FullOracleSnapshotManager(fixture.blue, true);
        DocumentProcessingRuntime incremental = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                incrementalManager,
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(fixture.blue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                oracleManager);

        // when
        JsonPatch patch = JsonPatch.replace(
                "/contracts/retained/processorState", new Node().value("busy"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

        // then
        assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
        assertEquals(1, incrementalManager.fullResolutions);
        assertEquals(1L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertEquals(1L, metrics.snapshot().counter(
                "fullSnapshotFallbackReason.CONTRACTS_CHANGED"));
        assertEquals(0L, metrics.snapshot().counter("incrementalSnapshotResolutions"));
    }

    @Test
    void shouldVerifyTypeContributionOnChangedPathUsesOneExplicitFullFallbackAndMatchesOracle() {
        // given
        Fixture fixture = Fixture.withFixedStatusSubtype();
        ResolvedSnapshot base = fixture.snapshot();
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        FullOracleSnapshotManager incrementalManager = new FullOracleSnapshotManager(fixture.blue, true);
        DocumentProcessingRuntime incremental = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                incrementalManager,
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(fixture.blue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                oracleManager);

        // when
        JsonPatch patch = JsonPatch.replace("/status", new Node().value("published"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

        // then
        assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
        assertEquals(fixture.parentTypeId,
                incremental.snapshot().canonicalRoot().getAsText("/type/blueId"));
        assertEquals(1, incrementalManager.fullResolutions);
        assertEquals(1L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertEquals(1L, metrics.snapshot().counter(
                "fullSnapshotFallbackReason.TYPE_GRAPH_CHANGED"));
        assertEquals(0L, metrics.snapshot().counter("incrementalSnapshotResolutions"));
    }

    @Test
    void shouldVerifySchemaBearingTypedLeafUsesOneExplicitFullFallbackAndMatchesOracle() {
        // given
        Fixture fixture = Fixture.withSchemaStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshot();
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        FullOracleSnapshotManager incrementalManager = new FullOracleSnapshotManager(fixture.blue, true);
        DocumentProcessingRuntime incremental = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                incrementalManager,
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(fixture.blue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                oracleManager);

        // when
        JsonPatch patch = JsonPatch.replace("/status", new Node().value("published"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

        // then
        assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
        assertEquals(1, incrementalManager.fullResolutions);
        assertEquals(1L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertEquals(1L, metrics.snapshot().counter(
                "fullSnapshotFallbackReason.SCHEMA_GRAPH_CHANGED"));
        assertEquals(0L, metrics.snapshot().counter("incrementalSnapshotResolutions"));
    }

    @Test
    void shouldVerifyEmptyContractsNormalizationPreventsTheTypedLeafFastPathAndMatchesTheFullOracle() {
        // given
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshotWithEmptyContracts();
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        FullOracleSnapshotManager incrementalManager = new FullOracleSnapshotManager(fixture.blue, true);
        DocumentProcessingRuntime incremental = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                incrementalManager,
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(fixture.blue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                fixture.blue.conformanceEngine(),
                oracleManager);

        // when
        JsonPatch patch = JsonPatch.replace("/status", new Node().value("published"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

        // then
        assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
        assertEquals(1, incrementalManager.fullResolutions);
        assertEquals(1L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertEquals(1L, metrics.snapshot().counter(
                "fullSnapshotFallbackReason.UNBOUNDED_SIBLING_DEPENDENCY"));
        assertEquals(0L, metrics.snapshot().counter("incrementalSnapshotResolutions"));
    }

    @Test
    void shouldVerifyCustomMergingProcessorCannotOptIntoBuiltInIncrementalProof() {
        // given
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        MergingProcessor custom = new DelegatingMergingProcessor(fixture.blue.getMergingProcessor());

        // when
        ConformanceEngine customEngine = new ConformanceEngine(fixture.blue.getNodeProvider(), custom);
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        FullOracleSnapshotManager manager = new FullOracleSnapshotManager(fixture.blue, true);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.snapshot(), customEngine, manager, metrics);
        runtime.applyPatch("/", JsonPatch.replace("/status", new Node().value("confirmed")));
        boolean supportsIncremental =
                customEngine.supportsIncrementalValueResolution();
        ProcessingMetricsSnapshot snapshot =
                metrics.snapshot();

        // then
        assertFalse(supportsIncremental);
        assertEquals(1, manager.fullResolutions);
        assertEquals(1L, snapshot.counter("fullSnapshotFallbacks"));
        assertEquals(1L, snapshot.counter(
                "fullSnapshotFallbackReason.CUSTOM_MERGING_PROCESSOR"));
    }

    @Test
    void shouldVerifyRequestAwareTransparentWrapperAllowsIncrementalResolution() {
        // given
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        RequestAwareWrapper wrapper = new RequestAwareWrapper(
                fixture.blue.getMergingProcessor(), null);
        Blue wrappedBlue = new Blue(fixture.provider, wrapper);
        ResolvedSnapshot base = snapshot(wrappedBlue, fixture);
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                base,
                wrappedBlue.conformanceEngine(),
                wrappedBlue.getDocumentProcessor().snapshotManager(),
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(wrappedBlue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                wrappedBlue.conformanceEngine(),
                oracleManager);

        // when
        JsonPatch patch = JsonPatch.replace("/status", new Node().value("confirmed"));
        runtime.applyPatch("/", patch);
        oracle.applyPatch("/", patch);
        ResolvedSnapshot expectedSnapshot =
                oracle.snapshot();
        ResolvedSnapshot actualSnapshot =
                runtime.snapshot();
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        int requestCalls = wrapper.requestCalls;

        // then
        assertSnapshotEquals(wrappedBlue,
                expectedSnapshot, actualSnapshot);
        assertEquals(1L, snapshot.counter("incrementalSnapshotResolutions"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbacks"), snapshot.toString());
        assertEquals(1L, snapshot.counter("incrementalMergerCapabilityRequests"), snapshot.toString());
        assertEquals(1L, snapshot.counter("incrementalMergerCapabilityAllowed"), snapshot.toString());
        assertTrue(requestCalls >= 2,
                "both conformance and snapshot manager should consult the same request-aware capability");
    }

    @Test
    void shouldVerifyRequestAwareGuardedWrapperDeniesProtectedRegion() {
        // given
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        RequestAwareWrapper wrapper = new RequestAwareWrapper(
                fixture.blue.getMergingProcessor(), "/status");
        Blue wrappedBlue = new Blue(fixture.provider, wrapper);
        ResolvedSnapshot base = snapshot(wrappedBlue, fixture);
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        FullOracleSnapshotManager manager = new FullOracleSnapshotManager(wrappedBlue, true);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                base,
                new ConformanceEngine(wrappedBlue.getNodeProvider(), wrapper),
                manager,
                metrics);

        // when
        runtime.applyPatch("/", JsonPatch.replace("/status", new Node().value("confirmed")));
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        int fullResolutions = manager.fullResolutions;

        // then
        assertEquals(1L, snapshot.counter("fullSnapshotFallbacks"), snapshot.toString());
        assertEquals(1L, snapshot.counter("fullSnapshotFallbackReason.CUSTOM_MERGING_PROCESSOR"), snapshot.toString());
        assertEquals(1L, snapshot.counter("incrementalMergerCapabilityRequests"), snapshot.toString());
        assertEquals(1L, snapshot.counter("incrementalMergerCapabilityDenied"), snapshot.toString());
        assertEquals(1L, snapshot.counter("incrementalMergerCapabilityDeniedByConformance"), snapshot.toString());
        assertEquals(0L, snapshot.counter("incrementalMergerCapabilityDeniedBySnapshotManager"), snapshot.toString());
        assertEquals(1, fullResolutions);
    }

    @Test
    void shouldVerifyDishonestCapabilityDemonstratesTruthfulWrapperContract() {
        // given
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        DishonestWrapper wrapper = new DishonestWrapper(fixture.blue.getMergingProcessor());
        Blue wrappedBlue = new Blue(fixture.provider, wrapper);
        ResolvedSnapshot base = snapshot(wrappedBlue, fixture);
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        DocumentProcessingRuntime incremental = new DocumentProcessingRuntime(
                base,
                wrappedBlue.conformanceEngine(),
                wrappedBlue.getDocumentProcessor().snapshotManager(),
                metrics);
        FullOracleSnapshotManager oracleManager = new FullOracleSnapshotManager(wrappedBlue, false);
        DocumentProcessingRuntime oracle = new DocumentProcessingRuntime(
                base,
                wrappedBlue.conformanceEngine(),
                oracleManager);

        // when
        JsonPatch patch = JsonPatch.replace("/status", new Node().value("confirmed"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

        // then
        assertEquals(1L, metrics.snapshot().counter("incrementalSnapshotResolutions"));
        assertEquals(0L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertNotEquals(wrappedBlue.nodeToJson(oracle.snapshot().resolvedRoot()),
                wrappedBlue.nodeToJson(incremental.snapshot().resolvedRoot()),
                "Language trusts request-aware capabilities and does not run an expensive dishonesty oracle");
    }


    @Test
    void shouldVerifyImpactModelCarriesTypedBoundaryAndDependencyEvidence() {
        // given
        Fixture fixture = Fixture.withFixedStatusSubtype();
        ResolvedSnapshot base = fixture.snapshot();
        ImmutableJsonPatch patch = ImmutableJsonPatch.from(
                JsonPatch.replace("/status", new Node().value("published")),
                base.frozenCanonicalRoot(),
                base.frozenResolvedRoot());
        ImmutablePatchPlanner.PatchPlan canonicalPlan =
                ImmutablePatchPlanner.forFrozen(base.frozenCanonicalRoot())
                        .planWithExactReplacement("/", patch);
        ImmutablePatchPlanner.PatchPlan resolvedPlan =
                ImmutablePatchPlanner.forFrozen(base.frozenResolvedRoot())
                        .planWithExactReplacement("/", patch);
        FullOracleSnapshotManager manager = new FullOracleSnapshotManager(fixture.blue, true);
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();

        // when
        PatchImpact impact = new PatchImpactAnalyzer(
                fixture.blue.conformanceEngine(), null, manager, metrics)
                .analyze(true,
                        base.frozenCanonicalRoot(),
                        base.frozenResolvedRoot(),
                        canonicalPlan,
                        resolvedPlan,
                        patch);

        // then
        assertEquals(PatchImpact.Kind.VALUE_ONLY, impact.kind());
        assertEquals("/status", impact.path().pointer());
        assertEquals(PatchImpact.Shape.SCALAR, impact.beforeShape());
        assertEquals(PatchImpact.Shape.SCALAR, impact.afterShape());
        assertTrue(impact.ancestorChain().contains("/"));
        assertTrue(impact.affectedTypedBoundaries().contains("/"));
        assertTrue(impact.typeDependency());
        assertFalse(impact.localResolutionProvenSafe());
        assertEquals(PatchImpact.FallbackReason.TYPE_GRAPH_CHANGED, impact.fallbackReason());
        assertEquals(1L, metrics.snapshot().counter("patchImpactAnalyses"));
        assertEquals(1L, metrics.snapshot().counter("patchImpactValueOnly"));
    }

    @Test
    void shouldNeverTreatApplicationContractRemovalAsLocallySafeWhenStructuresMatch() {
        // given
        FrozenNode root = FrozenNode.fromResolvedNode(new Node()
                .contracts(new Node().properties(
                        "requiredWorkflow",
                        new Node().properties(
                                "operationId",
                                new Node().value("not-selected")))));
        ImmutableJsonPatch patch = ImmutableJsonPatch.from(
                JsonPatch.remove("/contracts/requiredWorkflow"),
                root,
                root);
        ImmutablePatchPlanner.PatchPlan canonicalPlan =
                ImmutablePatchPlanner.forFrozen(root)
                        .planWithExactReplacement("/", patch);
        ImmutablePatchPlanner.PatchPlan resolvedPlan =
                ImmutablePatchPlanner.forFrozen(root)
                        .planWithExactReplacement("/", patch);

        // when
        PatchImpact impact = new PatchImpactAnalyzer(
                null, null, null, NoOpProcessingObserver.INSTANCE)
                .analyze(
                        true,
                        root,
                        root,
                        canonicalPlan,
                        resolvedPlan,
                        patch);

        // then
        assertEquals(
                PatchImpact.Kind.CONTRACT_OR_PROCESSING_STRUCTURE,
                impact.kind());
        assertFalse(impact.localResolutionProvenSafe());
        assertEquals(
                PatchImpact.FallbackReason.CONTRACTS_CHANGED,
                impact.fallbackReason());
    }

    private static void assertSnapshotEquals(Blue blue,
                                             ResolvedSnapshot expected,
                                             ResolvedSnapshot actual) {
        assertEquals(blue.nodeToJson(expected.canonicalRoot()),
                blue.nodeToJson(actual.canonicalRoot()));
        assertEquals(blue.nodeToJson(expected.resolvedRoot()),
                blue.nodeToJson(actual.resolvedRoot()));
        assertEquals(expected.blueId(), actual.blueId());
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static ResolvedSnapshot snapshot(Blue blue, Fixture fixture) {
        return blue.resolveToSnapshot(new Node()
                .type(reference(fixture.documentTypeId))
                .properties("status", new Node().value("draft")));
    }

    private static final class Fixture {
        private final BasicNodeProvider provider;
        private final Blue blue;
        private final String documentTypeId;
        private final String parentTypeId;

        private Fixture(BasicNodeProvider provider,
                        Blue blue,
                        String documentTypeId,
                        String parentTypeId) {
            this.provider = provider;
            this.blue = blue;
            this.documentTypeId = documentTypeId;
            this.parentTypeId = parentTypeId;
        }

        private static Fixture withUnrelatedTypeContribution() {
            BasicNodeProvider provider = new BasicNodeProvider();
            provider.addSingleNodes(new Node()
                    .name("Incremental Document")
                    .properties("inheritedUnrelated", new Node().value("retained")));
            String typeId = provider.getBlueIdByName("Incremental Document");
            return new Fixture(provider, new Blue(provider), typeId, null);
        }

        private static Fixture withFixedStatusSubtype() {
            BasicNodeProvider provider = new BasicNodeProvider();
            provider.addSingleNodes(new Node().name("Publishable Document"));
            String parentId = provider.getBlueIdByName("Publishable Document");
            provider.addSingleNodes(new Node()
                    .name("Draft Document")
                    .type(reference(parentId))
                    .properties("status", new Node().value("draft")));
            String draftId = provider.getBlueIdByName("Draft Document");
            return new Fixture(provider, new Blue(provider), draftId, parentId);
        }

        private static Fixture withBasicStatusTypeContribution() {
            BasicNodeProvider provider = new BasicNodeProvider();
            provider.addSingleNodes(new Node()
                    .name("Basic Typed Status Document")
                    .properties(
                            "status", new Node().type(reference(TEXT_TYPE_BLUE_ID)),
                            "inheritedUnrelated", new Node().value("retained")));
            String typeId = provider.getBlueIdByName("Basic Typed Status Document");
            return new Fixture(provider, new Blue(provider), typeId, null);
        }

        private static Fixture withSchemaStatusTypeContribution() {
            BasicNodeProvider provider = new BasicNodeProvider();
            provider.addSingleNodes(new Node()
                    .name("Schema Typed Status Document")
                    .properties("status", new Node()
                            .type(reference(TEXT_TYPE_BLUE_ID))
                            .schema(new Schema().minLength(1))));
            String typeId = provider.getBlueIdByName("Schema Typed Status Document");
            return new Fixture(provider, new Blue(provider), typeId, null);
        }

        private ResolvedSnapshot snapshot() {
            return blue.resolveToSnapshot(new Node()
                    .type(reference(documentTypeId))
                    .properties("status", new Node().value("draft")));
        }

        private ResolvedSnapshot snapshotWithEmptyContracts() {
            return blue.resolveToSnapshot(new Node()
                    .type(reference(documentTypeId))
                    .properties("status", new Node().value("draft"))
                    .contracts(new Node()));
        }

        private ResolvedSnapshot snapshotWithNonEmptyContracts() {
            return blue.resolveToSnapshot(new Node()
                    .type(reference(documentTypeId))
                    .properties("status", new Node().value("draft"))
                    .contracts(new Node().properties(
                            "retained", new Node().properties(
                                    "processorState", new Node().value("idle")))));
        }
    }

    private static final class PatchObservation {
        private final ResolvedSnapshot expectedSnapshot;
        private final ResolvedSnapshot actualSnapshot;
        private final DocumentUpdateData expectedUpdate;
        private final DocumentUpdateData actualUpdate;
        private final FrozenNode resolvedChangedNode;
        private final FrozenNode unaffectedNode;

        private PatchObservation(
                ResolvedSnapshot expectedSnapshot,
                ResolvedSnapshot actualSnapshot,
                DocumentUpdateData expectedUpdate,
                DocumentUpdateData actualUpdate,
                FrozenNode resolvedChangedNode,
                FrozenNode unaffectedNode) {
            this.expectedSnapshot = expectedSnapshot;
            this.actualSnapshot = actualSnapshot;
            this.expectedUpdate = expectedUpdate;
            this.actualUpdate = actualUpdate;
            this.resolvedChangedNode = resolvedChangedNode;
            this.unaffectedNode = unaffectedNode;
        }
    }

    private static final class FullOracleSnapshotManager implements ProcessingSnapshotManager {
        private final Blue blue;
        private final boolean incrementalCapability;
        private int fullResolutions;

        private FullOracleSnapshotManager(Blue blue, boolean incrementalCapability) {
            this.blue = blue;
            this.incrementalCapability = incrementalCapability;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return fromDocumentTransient(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            fullResolutions++;
            return blue.resolveToSnapshot(document);
        }

        @Override
        public boolean supportsIncrementalValueResolution() {
            return incrementalCapability;
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            throw new AssertionError("planning must use the canonical authoritative oracle");
        }
    }

    private static final class DelegatingMergingProcessor implements MergingProcessor {
        private final MergingProcessor delegate;

        private DelegatingMergingProcessor(MergingProcessor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver,
                            CanonicalTypeIdentityLookup typeIdentities) {
            delegate.process(target, source, nodeProvider, nodeResolver,
                    typeIdentities);
        }

        @Override
        public void postProcess(Node target,
                                Node source,
                                NodeProvider nodeProvider,
                                NodeResolver nodeResolver,
                                CanonicalTypeIdentityLookup typeIdentities) {
            delegate.postProcess(target, source, nodeProvider, nodeResolver,
                    typeIdentities);
        }

        @Override
        public boolean hasCompletedValidation(Node node) {
            return delegate.hasCompletedValidation(node);
        }

        @Override
        public boolean requiresReferenceMaterialization(Node node) {
            return delegate.requiresReferenceMaterialization(node);
        }

        @Override
        public void validateCompleted(
                Node node,
                boolean semanticallyPresent,
                String path,
                CanonicalTypeIdentityLookup typeIdentities) {
            delegate.validateCompleted(
                    node, semanticallyPresent, path, typeIdentities);
        }
    }

    private static final class RequestAwareWrapper implements MergingProcessor, IncrementalMergingProcessorCapability {
        private final MergingProcessor delegate;
        private final String deniedPath;
        private int requestCalls;

        private RequestAwareWrapper(MergingProcessor delegate, String deniedPath) {
            this.delegate = delegate;
            this.deniedPath = deniedPath;
        }

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver,
                            CanonicalTypeIdentityLookup typeIdentities) {
            delegate.process(target, source, nodeProvider, nodeResolver,
                    typeIdentities);
        }

        @Override
        public void postProcess(Node target,
                                Node source,
                                NodeProvider nodeProvider,
                                NodeResolver nodeResolver,
                                CanonicalTypeIdentityLookup typeIdentities) {
            delegate.postProcess(target, source, nodeProvider, nodeResolver,
                    typeIdentities);
        }

        @Override
        public boolean hasCompletedValidation(Node node) {
            return delegate.hasCompletedValidation(node);
        }

        @Override
        public boolean requiresReferenceMaterialization(Node node) {
            return delegate.requiresReferenceMaterialization(node);
        }

        @Override
        public void validateCompleted(
                Node node,
                boolean semanticallyPresent,
                String path,
                CanonicalTypeIdentityLookup typeIdentities) {
            delegate.validateCompleted(
                    node, semanticallyPresent, path, typeIdentities);
        }

        @Override
        public boolean supportsIncrementalValueResolution() {
            return false;
        }

        @Override
        public boolean supportsIncrementalValueResolution(IncrementalValueResolutionRequest request) {
            requestCalls++;
            if (deniedPath != null && deniedPath.equals(request.changedPath())) {
                return false;
            }
            return delegate instanceof IncrementalMergingProcessorCapability
                    && ((IncrementalMergingProcessorCapability) delegate)
                    .supportsIncrementalValueResolution(request);
        }
    }

    private static final class DishonestWrapper implements MergingProcessor, IncrementalMergingProcessorCapability {
        private final MergingProcessor delegate;

        private DishonestWrapper(MergingProcessor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver,
                            CanonicalTypeIdentityLookup typeIdentities) {
            delegate.process(target, source, nodeProvider, nodeResolver,
                    typeIdentities);
        }

        @Override
        public void postProcess(Node target,
                                Node source,
                                NodeProvider nodeProvider,
                                NodeResolver nodeResolver,
                                CanonicalTypeIdentityLookup typeIdentities) {
            delegate.postProcess(target, source, nodeProvider, nodeResolver,
                    typeIdentities);
            if (target.getProperties() != null
                    && target.getProperties().get("status") != null
                    && target.getProperties().get("status").getValue() != null) {
                target.properties("wrapperObservedStatus",
                        new Node().value(String.valueOf(target.getProperties().get("status").getValue())));
            }
        }

        @Override
        public boolean hasCompletedValidation(Node node) {
            return delegate.hasCompletedValidation(node);
        }

        @Override
        public boolean requiresReferenceMaterialization(Node node) {
            return delegate.requiresReferenceMaterialization(node);
        }

        @Override
        public void validateCompleted(
                Node node,
                boolean semanticallyPresent,
                String path,
                CanonicalTypeIdentityLookup typeIdentities) {
            delegate.validateCompleted(
                    node, semanticallyPresent, path, typeIdentities);
        }

        @Override
        public boolean supportsIncrementalValueResolution() {
            return true;
        }

        @Override
        public boolean supportsIncrementalValueResolution(IncrementalValueResolutionRequest request) {
            return true;
        }
    }
}
