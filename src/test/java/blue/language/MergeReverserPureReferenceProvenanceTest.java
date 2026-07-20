package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.MergeReverser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeReverserPureReferenceProvenanceTest {

    @Test
    void minimizedOverlayPreservesSourceReferenceMaterializedUnderInheritedMetadata() {
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
        Node canonicalReference = original.canonicalRoot().getAsNode("/prevEntry");
        Node resolvedReference = original.resolvedRoot().getAsNode("/prevEntry");

        assertTrue(canonicalReference.isReferenceOnly());
        assertFalse(resolvedReference.isReferenceOnly());
        assertEquals(referencedBlueId, resolvedReference.getBlueId());

        Node minimized = new MergeReverser().reverseToMinimizedOverlay(original.resolvedRoot());
        Node minimizedReference = minimized.getProperties() == null
                ? null
                : minimized.getProperties().get("prevEntry");

        assertNotNull(minimizedReference, writer.nodeToJson(minimized));
        assertTrue(minimizedReference.isReferenceOnly(), minimizedReference::toString);
        assertEquals(referencedBlueId, minimizedReference.getBlueId());

        BasicNodeProvider readerProvider = provider();
        Blue reader = new Blue(readerProvider);
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(
                reader.jsonToNode(writer.nodeToJson(minimized)));

        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(
                writer.nodeToJson(original.resolvedRoot()),
                reader.nodeToJson(reloaded.resolvedRoot()));
    }

    @Test
    void minimizedOverlayOmitsReferenceFullyInheritedFromType() {
        BasicNodeProvider writerProvider = providerWithInheritedReference();
        String holderTypeBlueId = writerProvider.getBlueIdByName("Holder With Inherited Reference");
        Blue writer = new Blue(writerProvider);
        ResolvedSnapshot original = writer.resolveToSnapshot(writer.yamlToNode(
                "type:\n" +
                "  blueId: " + holderTypeBlueId));

        Node minimized = new MergeReverser().reverseToMinimizedOverlay(original.resolvedRoot());

        assertTrue(minimized.getProperties() == null
                || !minimized.getProperties().containsKey("prevEntry"));
        BasicNodeProvider readerProvider = providerWithInheritedReference();
        Blue reader = new Blue(readerProvider);
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(
                reader.jsonToNode(writer.nodeToJson(minimized)));
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(
                original.frozenResolvedRoot().resolvedStructuralKey(),
                reloaded.frozenResolvedRoot().resolvedStructuralKey());
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
