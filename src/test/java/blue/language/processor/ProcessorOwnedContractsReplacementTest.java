package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.conformance.contracts.ContractsConformanceSuite;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for application replacement of processor-owned state. */
final class ProcessorOwnedContractsReplacementTest {

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    @Test
    void shouldRetainCanonicalMarkerWhenWholeContractsReplacementUsesEquivalentInlineWitness() {
        // given
        Node exactDocument = new Node()
                .name("exact pre-initialization witness");
        String exactDocumentBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactDocument);
        Node initialized = initializedMarker(
                new Node().blueId(exactDocumentBlueId));
        Node before = new Node().contracts(new Node().properties(
                "initialized", initialized,
                "oldApplicationContract", new Node().name("old")));
        FrozenNode canonical = FrozenNode.fromNode(before);
        FrozenNode resolved = FrozenNode.fromResolvedNode(before);
        FrozenNode retainedCanonical = canonical.at(
                "/contracts/initialized");
        FrozenNode retainedResolved = resolved.at(
                "/contracts/initialized");
        Node unrelatedExact = new Node().name("unrelated exact contract");
        String unrelatedBlueId =
                DirectBlueIdCalculator.calculateBlueId(unrelatedExact);
        Node replacement = new Node().properties(
                "initialized", initializedMarker(exactDocument.clone()),
                "newApplicationContract", new Node().blueId(unrelatedBlueId));
        String replacementMarkerBlueId = FrozenNode.fromNode(
                replacement.getProperties().get("initialized")).blueId();
        PatchPlanningContext planning =
                PatchPlanningContextFactory.create(
                        canonical,
                        resolved,
                        false,
                        null);
        PatchPlanningEngine engine = new PatchPlanningEngine(
                "/", planning, null, null, null);

        // when
        BatchPatchResult result = engine.planAtomic(
                Collections.singletonList(JsonPatch.replace(
                        "/contracts", replacement)),
                false);

        // then
        assertEquals(
                retainedCanonical.blueId(),
                replacementMarkerBlueId,
                "the guard-admitted inline witness must be identity-equivalent");
        FrozenNode resultingCanonical = result.canonicalRoot().at(
                "/contracts/initialized");
        FrozenNode resultingResolved = result.resolvedRoot().at(
                "/contracts/initialized");
        assertTrue(retainedCanonical.sameResolvedStructure(
                        resultingCanonical),
                "the admitted application representation must not replace "
                        + "the canonical processor marker");
        assertTrue(retainedResolved.sameResolvedStructure(
                        resultingResolved),
                "the resolved processor marker lane must be retained too");
        assertEquals(retainedCanonical.blueId(),
                resultingCanonical.blueId());
        FrozenNode retainedWitness = resultingCanonical.at("/document");
        assertTrue(retainedWitness.isReferenceOnly());
        assertEquals(exactDocumentBlueId,
                retainedWitness.getReferenceBlueId());
        FrozenNode unrelated = result.canonicalRoot().at(
                "/contracts/newApplicationContract");
        assertTrue(unrelated.isReferenceOnly(),
                "an unrelated exact application contract stays untouched");
        assertEquals(unrelatedBlueId, unrelated.getReferenceBlueId());
        assertNull(result.canonicalRoot().at(
                        "/contracts/oldApplicationContract"),
                "whole-contracts replacement must remove old application "
                        + "contracts from the canonical candidate");
        assertNull(result.resolvedRoot().at(
                        "/contracts/oldApplicationContract"),
                "whole-contracts replacement must remove old application "
                        + "contracts from the resolved candidate");
    }

    @Test
    void shouldCatalogOnlyInitializedExactWitnessAsProcessorStateReference() {
        // given
        String witnessBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("witness"));
        String unrelatedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("unrelated"));
        Node document = new Node().contracts(new Node().properties(
                "initialized", initializedMarker(
                        new Node().blueId(witnessBlueId)),
                "applicationContract", new Node().blueId(unrelatedBlueId)));

        // when
        Set<String> paths =
                ExecutableBodyPathCatalog.processorStateReferencePaths(
                        document, Collections.singleton("/"));

        // then
        assertEquals(Collections.singleton(
                "/contracts/initialized/document"), paths);
    }

    @Test
    void shouldCatalogInitializedWitnessInsideUnappliedContractsPatch() {
        // given
        Node patch = new Node().properties(
                "op", new Node().value("replace"),
                "path", new Node().value("/nested/contracts"),
                "val", new Node().properties(
                        "initialized", initializedMarker(
                                new Node().name(
                                        "exact pre-initialization witness"))));
        Node executableBody = new Node().properties(
                "patches", new Node().items(Arrays.asList(patch)));

        // when
        Set<String> paths = ExecutableBodyPathCatalog
                .processorStatePatchEffectPaths(executableBody);

        // then
        assertEquals(Collections.singleton(
                "/patches/0/val/initialized/document"), paths);
    }

    @Test
    void shouldCatalogWitnessInsideUnappliedInitializedPatch() {
        // given
        Node executableBody = executableBodyPatch(
                "add",
                "/nested/contracts/initialized",
                initializedMarker(new Node().name("exact witness")));

        // when
        Set<String> paths = ExecutableBodyPathCatalog
                .processorStatePatchEffectPaths(executableBody);

        // then
        assertEquals(Collections.singleton("/patches/0/val/document"),
                paths);
    }

    @Test
    void shouldCatalogWitnessInsideUnappliedInitializedDocumentPatch() {
        // given
        Node executableBody = executableBodyPatch(
                "replace",
                "/nested/contracts/initialized/document",
                new Node().name("exact witness"));

        // when
        Set<String> paths = ExecutableBodyPathCatalog
                .processorStatePatchEffectPaths(executableBody);

        // then
        assertEquals(Collections.singleton("/patches/0/val"), paths);
    }

    @Test
    void shouldIgnorePatchShapedApplicationDataWithoutSupportedOperation() {
        // given
        Node executableBody = executableBodyPatch(
                "equals",
                "/nested/contracts",
                new Node().properties(
                        "initialized", initializedMarker(
                                new Node().name("ordinary data"))));

        // when
        Set<String> paths = ExecutableBodyPathCatalog
                .processorStatePatchEffectPaths(executableBody);

        // then
        assertFalse(paths.iterator().hasNext());
    }

    @Test
    void shouldIgnorePatchShapedDataOutsideNormalizedPatchesList() {
        // given
        Node patchShapedData = patch(
                "replace",
                "/nested/contracts",
                new Node().properties(
                        "initialized", initializedMarker(
                                new Node().name("ordinary data"))));
        Node executableBody = new Node().properties(
                "audit", patchShapedData);

        // when
        Set<String> paths = ExecutableBodyPathCatalog
                .processorStatePatchEffectPaths(executableBody);

        // then
        assertTrue(paths.isEmpty());
    }

    @Test
    void shouldIgnorePatchEffectsWithInvalidRuntimePointers() {
        // given
        Node patches = new Node().items(Arrays.asList(
                patch("replace", "nested/contracts", contractsValue()),
                patch("replace", "/nested//contracts", contractsValue()),
                patch("replace", "/nested/contracts/", contractsValue()),
                patch("replace", "/nested/~2/contracts", contractsValue())));
        Node executableBody = new Node().properties("patches", patches);

        // when
        Set<String> paths = ExecutableBodyPathCatalog
                .processorStatePatchEffectPaths(executableBody);

        // then
        assertTrue(paths.isEmpty());
    }

    @Test
    void shouldCatalogWitnessInsideWholeScopeReplacement() {
        // given
        Node replacement = new Node()
                .properties("value", new Node().value(1))
                .contracts(contractsValue());
        Node executableBody = executableBodyPatch(
                "replace", "/child", replacement);

        // when
        Set<String> paths = ExecutableBodyPathCatalog
                .processorStatePatchEffectPaths(executableBody);

        // then
        assertEquals(Collections.singleton(
                "/patches/0/val/contracts/initialized/document"), paths);
    }

    @Test
    void shouldExecuteWholeContractsReplacementFixtureCEvo08()
            throws IOException {
        // given
        JsonNode fixture;
        try (InputStream stream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(
                        "blue-contracts-closure-1.0/fixtures/evo/"
                                + "c-evo-08.yaml")) {
            if (stream == null) {
                throw new IllegalStateException("Missing C-EVO-08 fixture");
            }
            fixture = YAML.readTree(stream);
        }

        // when
        ContractsConformanceSuite.runFixture(fixture);

        // then
        assertEquals("c-evo-08", fixture.path("id").asText());
    }

    private static Node initializedMarker(Node document) {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties("document", document);
    }

    private static Node executableBodyPatch(
            String operation,
            String path,
            Node value) {
        return new Node().properties(
                "patches", new Node().items(Arrays.asList(
                        patch(operation, path, value))));
    }

    private static Node patch(
            String operation,
            String path,
            Node value) {
        return new Node().properties(
                "op", new Node().value(operation),
                "path", new Node().value(path),
                "val", value);
    }

    private static Node contractsValue() {
        return new Node().properties(
                "initialized", initializedMarker(
                        new Node().name("exact witness")));
    }
}
