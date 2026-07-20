package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

class PatchImpactIncrementalResolutionTest {

    @Test
    void dependencyFreeTypedScalarReplacementMatchesFullOracleAfterEveryPatch() {
        Fixture fixture = Fixture.withUnrelatedTypeContribution();
        ResolvedSnapshot base = fixture.snapshot();
        FrozenNode unaffected = base.resolvedAt("/inheritedUnrelated");
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();

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
        for (JsonPatch patch : patches) {
            DocumentProcessingRuntime.DocumentUpdateData incrementalUpdate =
                    incremental.applyPatch("/", patch);
            DocumentProcessingRuntime.DocumentUpdateData oracleUpdate =
                    oracle.applyPatch("/", patch);

            assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
            assertEquals(fixture.blue.nodeToJson(oracleUpdate.before()),
                    fixture.blue.nodeToJson(incrementalUpdate.before()));
            assertEquals(fixture.blue.nodeToJson(oracleUpdate.after()),
                    fixture.blue.nodeToJson(incrementalUpdate.after()));
            assertEquals(oracleUpdate.path(), incrementalUpdate.path());
            assertEquals(oracleUpdate.op(), incrementalUpdate.op());
        }

        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(3L, snapshot.counter("patchImpactAnalyses"));
        assertEquals(3L, snapshot.counter("patchImpactValueOnly"), snapshot.toString());
        assertEquals(3L, snapshot.counter("incrementalSnapshotResolutions"));
        assertEquals(0L, snapshot.counter("fullSnapshotFallbacks"));
        assertEquals(0L, snapshot.counter("fullCanonicalRootMaterializations"));
        assertEquals(0L, snapshot.counter("fullResolvedRootMaterializations"));
        assertEquals(0L, snapshot.counter("conformancePlans"));
        assertEquals(3, oracleManager.fullResolutions);
        assertSame(unaffected, incremental.snapshot().resolvedAt("/inheritedUnrelated"),
                "the incremental splice must retain an unrelated resolved subtree by identity");
    }

    @Test
    void basicTypedLeafReplacementPreservesResolvedMetadataAndMatchesFullOracleAfterEveryPatch() {
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshot();
        FrozenNode unaffected = base.resolvedAt("/inheritedUnrelated");
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
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
        for (JsonPatch patch : patches) {
            DocumentProcessingRuntime.DocumentUpdateData incrementalUpdate =
                    incremental.applyPatch("/", patch);
            DocumentProcessingRuntime.DocumentUpdateData oracleUpdate =
                    oracle.applyPatch("/", patch);

            assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
            assertEquals(fixture.blue.nodeToJson(oracleUpdate.before()),
                    fixture.blue.nodeToJson(incrementalUpdate.before()));
            assertEquals(fixture.blue.nodeToJson(oracleUpdate.after()),
                    fixture.blue.nodeToJson(incrementalUpdate.after()));
            FrozenNode resolvedStatus = incremental.snapshot().resolvedAt("/status");
            assertEquals(TEXT_TYPE_BLUE_ID, resolvedStatus.getType().getReferenceBlueId());
            assertSame(unaffected, incremental.snapshot().resolvedAt("/inheritedUnrelated"));
        }

        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
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
    void nonEmptyProcessorContractsRemainSharedAcrossTypedLeafFastPathPatches() {
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshotWithNonEmptyContracts();
        FrozenNode unaffectedContract = base.resolvedAt("/contracts/retained");
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
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
        for (JsonPatch patch : patches) {
            DocumentProcessingRuntime.DocumentUpdateData incrementalUpdate =
                    incremental.applyPatch("/", patch);
            DocumentProcessingRuntime.DocumentUpdateData oracleUpdate =
                    oracle.applyPatch("/", patch);

            assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
            assertEquals(fixture.blue.nodeToJson(oracleUpdate.before()),
                    fixture.blue.nodeToJson(incrementalUpdate.before()));
            assertEquals(fixture.blue.nodeToJson(oracleUpdate.after()),
                    fixture.blue.nodeToJson(incrementalUpdate.after()));
            assertSame(unaffectedContract,
                    incremental.snapshot().resolvedAt("/contracts/retained"));
        }

        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(3L, snapshot.counter("incrementalSnapshotResolutions"));
        assertEquals(0L, snapshot.counter("fullSnapshotFallbacks"));
        assertEquals(0L, snapshot.counter("fullCanonicalRootMaterializations"));
        assertEquals(0L, snapshot.counter("fullResolvedRootMaterializations"));
        assertEquals(0, incrementalManager.fullResolutions);
        assertEquals(3, oracleManager.fullResolutions);
    }

    @Test
    void patchUnderContractsUsesNamedFullFallbackAndMatchesOracle() {
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshotWithNonEmptyContracts();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
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

        JsonPatch patch = JsonPatch.replace(
                "/contracts/retained/processorState", new Node().value("busy"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

        assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
        assertEquals(1, incrementalManager.fullResolutions);
        assertEquals(1L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertEquals(1L, metrics.snapshot().counter(
                "fullSnapshotFallbackReason.CONTRACTS_CHANGED"));
        assertEquals(0L, metrics.snapshot().counter("incrementalSnapshotResolutions"));
    }

    @Test
    void typeContributionOnChangedPathUsesOneExplicitFullFallbackAndMatchesOracle() {
        Fixture fixture = Fixture.withFixedStatusSubtype();
        ResolvedSnapshot base = fixture.snapshot();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
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

        JsonPatch patch = JsonPatch.replace("/status", new Node().value("published"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

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
    void schemaBearingTypedLeafUsesOneExplicitFullFallbackAndMatchesOracle() {
        Fixture fixture = Fixture.withSchemaStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshot();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
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

        JsonPatch patch = JsonPatch.replace("/status", new Node().value("published"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

        assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
        assertEquals(1, incrementalManager.fullResolutions);
        assertEquals(1L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertEquals(1L, metrics.snapshot().counter(
                "fullSnapshotFallbackReason.SCHEMA_GRAPH_CHANGED"));
        assertEquals(0L, metrics.snapshot().counter("incrementalSnapshotResolutions"));
    }

    @Test
    void emptyContractsNormalizationPreventsTheTypedLeafFastPathAndMatchesTheFullOracle() {
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        ResolvedSnapshot base = fixture.snapshotWithEmptyContracts();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
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

        JsonPatch patch = JsonPatch.replace("/status", new Node().value("published"));
        incremental.applyPatch("/", patch);
        oracle.applyPatch("/", patch);

        assertSnapshotEquals(fixture.blue, oracle.snapshot(), incremental.snapshot());
        assertEquals(1, incrementalManager.fullResolutions);
        assertEquals(1L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertEquals(1L, metrics.snapshot().counter(
                "fullSnapshotFallbackReason.UNBOUNDED_SIBLING_DEPENDENCY"));
        assertEquals(0L, metrics.snapshot().counter("incrementalSnapshotResolutions"));
    }

    @Test
    void customMergingProcessorCannotOptIntoBuiltInIncrementalProof() {
        Fixture fixture = Fixture.withBasicStatusTypeContribution();
        MergingProcessor custom = new DelegatingMergingProcessor(fixture.blue.getMergingProcessor());
        ConformanceEngine customEngine = new ConformanceEngine(fixture.blue.getNodeProvider(), custom);
        assertFalse(customEngine.supportsIncrementalValueResolution());

        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        FullOracleSnapshotManager manager = new FullOracleSnapshotManager(fixture.blue, true);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.snapshot(), customEngine, manager, metrics);

        runtime.applyPatch("/", JsonPatch.replace("/status", new Node().value("confirmed")));

        assertEquals(1, manager.fullResolutions);
        assertEquals(1L, metrics.snapshot().counter("fullSnapshotFallbacks"));
        assertEquals(1L, metrics.snapshot().counter(
                "fullSnapshotFallbackReason.CUSTOM_MERGING_PROCESSOR"));
    }

    @Test
    void impactModelCarriesTypedBoundaryAndDependencyEvidence() {
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
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();

        PatchImpact impact = new PatchImpactAnalyzer(
                fixture.blue.conformanceEngine(), null, manager, metrics)
                .analyze(true,
                        base.frozenCanonicalRoot(),
                        base.frozenResolvedRoot(),
                        canonicalPlan,
                        resolvedPlan,
                        patch);

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
                            NodeResolver nodeResolver) {
            delegate.process(target, source, nodeProvider, nodeResolver);
        }

        @Override
        public void postProcess(Node target,
                                Node source,
                                NodeProvider nodeProvider,
                                NodeResolver nodeResolver) {
            delegate.postProcess(target, source, nodeProvider, nodeResolver);
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
        public void validateCompleted(Node node, boolean semanticallyPresent, String path) {
            delegate.validateCompleted(node, semanticallyPresent, path);
        }
    }
}
