package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.merge.Merger;
import blue.language.model.Node;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.registry.BootstrapProvider;
import blue.language.provider.PotentialBlueIdNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.utils.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ResolvedTypeCacheHistoryRegressionTest {

    @Test
    void shouldKeepResolvedTypeShapeIndependentOfReferenceCacheHistory() {
        // given
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        NodeProvider extensions = blueId -> {
            if (fixture.channelBlueId.equals(blueId)) {
                return Collections.singletonList(fixture.channelType.clone());
            }
            if (fixture.auditHandlerBlueId.equals(blueId)) {
                return Collections.singletonList(fixture.auditHandlerType.clone());
            }
            return null;
        };
        NodeProvider processingProvider = new SequentialNodeProvider(
                BootstrapProvider.INSTANCE,
                BlueRuntimeTypeRegistry.getDefault().asProcessorSnapshotProvider(),
                extensions,
                new PotentialBlueIdNodeProvider(fixture.provider));
        Node source = blue.preprocess(fixture.materializedSource().clone());

        Node cold = new Merger(blue.getMergingProcessor(), processingProvider, cache)
                .resolve(source.clone());
        // when
        Node warm = new Merger(blue.getMergingProcessor(), processingProvider, cache)
                .resolve(source.clone());

        // then
        assertNotNull(NodePathEditor.getOrNull(cold, "/type/contracts/audit/type/type/order"),
                "cold resolution must materialize the Handler field inherited from Contract");
        assertNotNull(NodePathEditor.getOrNull(warm, "/type/contracts/audit/type/type/order"),
                "warm resolution must retain the same inherited field");
        assertEquals(JSON_MAPPER.valueToTree(cold), JSON_MAPPER.valueToTree(warm),
                "resolved content must not depend on reference-cache history");
    }
}
