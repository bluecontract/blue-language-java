package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.MinimizedOverlayBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimizedOverlayPureReferenceProvenanceTest {

    @Test
    void shouldPreserveSourceReferenceMaterializedUnderInheritedMetadataInMinimizedOverlay() {
        // given
        BasicNodeProvider writerProvider = provider();
        String referencedBlueId = writerProvider.getBlueIdByName("Referenced Entry");
        String holderTypeBlueId = writerProvider.getBlueIdByName("Holder Type");
        Blue writer = new Blue(writerProvider);
        Node source = writer.yamlToNode(
                "type:\n" +
                "  blueId: " + holderTypeBlueId + "\n" +
                "prevEntry:\n" +
                "  blueId: " + referencedBlueId);

        ResolvedSnapshot original = writer.resolveToSnapshot(source);

        // when
        Node canonicalReference =
                original.canonicalRoot().getAsNode("/prevEntry");
        Node resolvedReference = original.resolvedRoot().getAsNode("/prevEntry");
        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());
        Node minimizedReference = minimized.getProperties() == null
                ? null
                : minimized.getProperties().get("prevEntry");
        BasicNodeProvider readerProvider = provider();
        Blue reader = new Blue(readerProvider);
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(
                reader.jsonToNode(writer.nodeToJson(minimized)));
        String minimizedJson = writer.nodeToJson(minimized);
        String originalResolvedJson =
                writer.nodeToJson(original.resolvedRoot());
        String reloadedResolvedJson =
                reader.nodeToJson(reloaded.resolvedRoot());

        // then
        assertTrue(canonicalReference.isReferenceOnly());
        assertFalse(resolvedReference.isReferenceOnly());
        assertEquals(referencedBlueId, resolvedReference.getBlueId());
        assertNotNull(minimizedReference, minimizedJson);
        assertTrue(minimizedReference.isReferenceOnly(), minimizedReference::toString);
        assertEquals(referencedBlueId, minimizedReference.getBlueId());
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(originalResolvedJson, reloadedResolvedJson);
    }

    @Test
    void shouldOmitFullyInheritedReferenceFromMinimizedOverlay() {
        // given
        BasicNodeProvider writerProvider = providerWithInheritedReference();
        String holderTypeBlueId = writerProvider.getBlueIdByName("Holder With Inherited Reference");
        Blue writer = new Blue(writerProvider);
        ResolvedSnapshot original = writer.resolveToSnapshot(writer.yamlToNode(
                "type:\n" +
                "  blueId: " + holderTypeBlueId));

        // when
        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());
        BasicNodeProvider readerProvider = providerWithInheritedReference();
        Blue reader = new Blue(readerProvider);
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(
                reader.jsonToNode(writer.nodeToJson(minimized)));
        boolean inheritedReferenceOmitted =
                minimized.getProperties() == null
                        || !minimized.getProperties()
                        .containsKey("prevEntry");
        Object originalStructuralKey =
                original.frozenResolvedRoot()
                        .resolvedStructuralKey();
        Object reloadedStructuralKey =
                reloaded.frozenResolvedRoot()
                        .resolvedStructuralKey();

        // then
        assertTrue(inheritedReferenceOmitted);
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(originalStructuralKey, reloadedStructuralKey);
    }

    private static BasicNodeProvider provider() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Referenced Entry\n" +
                "payload: retained");
        provider.addSingleDocs(
                "name: Holder Type\n" +
                "prevEntry:\n" +
                "  description: Opaque predecessor reference");
        return provider;
    }

    private static BasicNodeProvider providerWithInheritedReference() {
        BasicNodeProvider provider = provider();
        provider.addSingleDocs(
                "name: Holder With Inherited Reference\n" +
                "prevEntry:\n" +
                "  blueId: " + provider.getBlueIdByName("Referenced Entry"));
        return provider;
    }
}
