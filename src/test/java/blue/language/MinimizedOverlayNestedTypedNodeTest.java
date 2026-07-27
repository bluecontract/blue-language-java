package blue.language;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.MinimizedOverlayBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinimizedOverlayNestedTypedNodeTest {

    @Test
    void canonicalPatchOfTypedChildRoundTripsThroughMinimizedSource() {
        BasicNodeProvider writerProvider = provider();
        Blue writer = new Blue(writerProvider);
        String markerTypeBlueId = writerProvider.getBlueIdByName("Processing Marker");
        ResolvedSnapshot initial = writer.loadSnapshot(new Node());
        Node marker = new Node()
                .type(new Node().blueId(markerTypeBlueId))
                .properties("documentId", new Node().value("document-1"));

        ResolvedSnapshot patched = writer.applyCanonicalPatch(initial,
                JsonPatch.add("/contracts/initialized", marker));
        Node expectedCanonical = new Node().contracts(new Node().properties(
                "initialized", marker.clone()));
        assertEquals(writer.calculateBlueId(expectedCanonical), patched.blueId());
        assertCanonicalMarkerContainsOnlyInstanceContent(
                patched.canonicalRoot().getAsNode("/contracts/initialized"));

        Node minimized = new MinimizedOverlayBuilder().build(
                patched.resolvedRoot());

        BasicNodeProvider readerProvider = provider();
        Blue reader = new Blue(readerProvider);
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(
                reader.jsonToNode(writer.nodeToJson(minimized)));

        assertEquals(patched.blueId(), reloaded.blueId());
        assertEquals(writer.calculateBlueId(expectedCanonical), reloaded.blueId());
        assertCanonicalMarkerContainsOnlyInstanceContent(
                reloaded.canonicalRoot().getAsNode("/contracts/initialized"));
        assertEquals(patched.frozenResolvedRoot().resolvedStructuralKey(),
                reloaded.frozenResolvedRoot().resolvedStructuralKey());
    }

    @Test
    void minimizedOverlayOmitsTypeDerivedMetadataFromAnInstanceIntroducedTypedChild() {
        BasicNodeProvider writerProvider = provider();
        Blue writer = new Blue(writerProvider);
        String markerTypeBlueId = writerProvider.getBlueIdByName("Processing Marker");
        Node source = writer.yamlToNode(String.join("\n",
                "contracts:",
                "  initialized:",
                "    type:",
                "      blueId: " + markerTypeBlueId,
                "    documentId: document-1"));
        ResolvedSnapshot original = writer.resolveToSnapshot(source);

        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());

        Node minimizedDocumentId = minimized.getContracts().getProperties().get("initialized")
                .getProperties().get("documentId");
        assertNull(minimizedDocumentId.getDescription());
        assertNull(minimized.getContracts().getProperties().get("initialized")
                .getProperties().get("order"));

        BasicNodeProvider readerProvider = provider();
        Blue reader = new Blue(readerProvider);
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(
                reader.jsonToNode(writer.nodeToJson(minimized)));
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(original.frozenResolvedRoot().resolvedStructuralKey(),
                reloaded.frozenResolvedRoot().resolvedStructuralKey());
    }

    @Test
    void minimizedOverlayPreservesExplicitLabelsOnIntroducedTypedPropertiesContractsAndItems() {
        BasicNodeProvider writerProvider = provider();
        Blue writer = new Blue(writerProvider);
        String markerTypeBlueId = writerProvider.getBlueIdByName("Processing Marker");
        String labeledMarker = String.join("\n",
                "    name: Processing Marker",
                "    description: Type-derived marker metadata.",
                "    type:",
                "      blueId: " + markerTypeBlueId,
                "    documentId: LABEL");
        Node source = writer.yamlToNode(String.join("\n",
                "direct:",
                labeledMarker.replace("LABEL", "direct"),
                "contracts:",
                "  labeled:",
                labeledMarker.replace("LABEL", "contract"),
                "list:",
                "  items:",
                "  - name: Processing Marker",
                "    description: Type-derived marker metadata.",
                "    type:",
                "      blueId: " + markerTypeBlueId,
                "    documentId: item"));
        ResolvedSnapshot original = writer.resolveToSnapshot(source);

        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());

        assertExplicitMarkerLabels(minimized.getAsNode("/direct"));
        assertExplicitMarkerLabels(minimized.getAsNode("/contracts/labeled"));
        assertExplicitMarkerLabels(minimized.getAsNode("/list/0"));
        Blue reader = new Blue(provider());
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(
                reader.jsonToNode(writer.nodeToJson(minimized)));
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(original.frozenResolvedRoot().resolvedStructuralKey(),
                reloaded.frozenResolvedRoot().resolvedStructuralKey());
    }

    @Test
    void minimizedOverlayPreservesExplicitLabelsOnIntroducedInlineTypedProperty() {
        Blue writer = new Blue();
        Node source = writer.yamlToNode(String.join("\n",
                "inline:",
                "  name: Inline Marker",
                "  description: Inline marker metadata.",
                "  type:",
                "    name: Inline Marker",
                "    description: Inline marker metadata.",
                "    documentId:",
                "      type: Text",
                "  documentId: inline"));
        ResolvedSnapshot original = writer.resolveToSnapshot(source);

        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());

        Node minimizedInline = minimized.getAsNode("/inline");
        assertEquals("Inline Marker", minimizedInline.getName());
        assertEquals("Inline marker metadata.", minimizedInline.getDescription());
        Blue reader = new Blue();
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(
                reader.jsonToNode(writer.nodeToJson(minimized)));
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(original.frozenResolvedRoot().resolvedStructuralKey(),
                reloaded.frozenResolvedRoot().resolvedStructuralKey());
    }

    @Test
    void canonicalOverlayPreservesExplicitLabelsEqualToInheritedChildLabels() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Labeled Container",
                "child:",
                "  name: Declared Child",
                "  description: Declared child description.",
                "  type: Text"));
        Blue blue = new Blue(provider);
        String containerTypeBlueId = provider.getBlueIdByName("Labeled Container");
        Node unlabeledSource = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + containerTypeBlueId,
                "child: value"));
        Node explicitlyLabeledSource = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + containerTypeBlueId,
                "child:",
                "  name: Declared Child",
                "  description: Declared child description.",
                "  value: value"));

        ResolvedSnapshot unlabeled = blue.resolveToSnapshot(unlabeledSource);
        ResolvedSnapshot explicitlyLabeled = blue.resolveToSnapshot(explicitlyLabeledSource);

        assertNull(unlabeled.canonicalNodeAt("/child").getName());
        assertNull(unlabeled.canonicalNodeAt("/child").getDescription());
        assertEquals("Declared Child", explicitlyLabeled.canonicalNodeAt("/child").getName());
        assertEquals("Declared child description.",
                explicitlyLabeled.canonicalNodeAt("/child").getDescription());
        assertNotEquals(unlabeled.blueId(), explicitlyLabeled.blueId(),
                "explicit instance labels are identity content even when equal to inherited labels");
    }

    private static void assertCanonicalMarkerContainsOnlyInstanceContent(Node marker) {
        assertNotNull(marker.getType());
        assertNotNull(marker.getProperties().get("documentId"));
        assertNull(marker.getProperties().get("documentId").getDescription());
        assertNull(marker.getProperties().get("order"));
    }

    private static void assertExplicitMarkerLabels(Node marker) {
        assertEquals("Processing Marker", marker.getName());
        assertEquals("Type-derived marker metadata.", marker.getDescription());
    }

    private static BasicNodeProvider provider() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Processing Marker",
                "description: Type-derived marker metadata.",
                "order:",
                "  description: Type-derived execution order.",
                "  type: Integer",
                "documentId:",
                "  description: Type-derived document identity description.",
                "  type: Text"));
        return provider;
    }
}
