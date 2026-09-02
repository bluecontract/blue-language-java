package blue.language.merge;

import blue.language.codec.BlueFormat;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.resolve.MinimizedOverlayBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ListOverlayCanonicalIdentityParityTest {

    @Test
    void itemTypeProjectionTrustsOnlyExactPureReferenceIdentity() {
        String blueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Materialized item type source"));
        Node materialized = new Node()
                .name("Materialized item type")
                .blueId(blueId);
        ListOverlayMerger merger = new ListOverlayMerger(null, null);

        Node retainedMaterialized = merger.itemTypeReference(materialized);
        Node retainedReference = merger.itemTypeReference(
                new Node().blueId(blueId));

        assertNotSame(materialized, retainedMaterialized);
        assertFalse(retainedMaterialized.isReferenceOnly());
        assertEquals("Materialized item type",
                retainedMaterialized.getName());
        assertTrue(retainedReference.isReferenceOnly());
        assertEquals(blueId, retainedReference.getBlueId());
    }

    @Test
    void previousAnchorUsesCanonicalTypeIdentityForCompletedItems() {
        BasicNodeProvider provider = providerWithReferenceTypedAppendBase();
        Node base = provider.getNodeByName("Reference typed append base");
        String baseBlueId = provider.getBlueIdByName(
                "Reference typed append base");
        String itemTypeBlueId = provider.getBlueIdByName(
                "Canonical list item");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(
                base.getItems());

        try (BlueLanguage writer = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            Node source = writer.codec().parseSource(
                    "type:\n"
                            + "  blueId: " + baseBlueId + "\n"
                            + "items:\n"
                            + "  - $previous:\n"
                            + "      blueId: " + previousBlueId + "\n"
                            + "  - type:\n"
                            + "      blueId: " + itemTypeBlueId + "\n"
                            + "    marker: C\n",
                    BlueFormat.YAML);

            ResolvedSnapshot original = writer.snapshots().resolve(source);
            Node minimized = new MinimizedOverlayBuilder().build(
                    original.frozenResolvedRoot(),
                    original.canonicalTypeIdentities());

            assertEquals(previousBlueId,
                    minimized.getItems().get(0).getPreviousBlueId());

            try (BlueLanguage reader = BlueLanguage.builder()
                    .nodeProvider(provider)
                    .build()) {
                ResolvedSnapshot reloaded = reader.snapshots()
                        .resolve(minimized);
                assertEquals(original.blueId(), reloaded.blueId());
                assertTrue(original.frozenResolvedRoot()
                        .sameResolvedStructure(
                                reloaded.frozenResolvedRoot()));
                assertFalse(hasListControls(
                        reloaded.resolvedRoot().getItems()));
            }
        }
    }

    @Test
    void reorderDetectionUsesCanonicalEvidenceAcrossInlineAndReferenceTypes() {
        BasicNodeProvider provider = providerWithInlineTypedPositionalBase();
        String baseBlueId = provider.getBlueIdByName(
                "Inline typed positional base");
        String itemTypeBlueId = provider.getBlueIdByName(
                "Canonical list item");

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            Node reordered = language.codec().parseSource(
                    "type:\n"
                            + "  blueId: " + baseBlueId + "\n"
                            + "items:\n"
                            + "  - type:\n"
                            + "      blueId: " + itemTypeBlueId + "\n"
                            + "    marker: B\n"
                            + "  - type:\n"
                            + "      blueId: " + itemTypeBlueId + "\n"
                            + "    marker: A\n",
                    BlueFormat.YAML);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> language.resolution().resolve(reordered));

            assertTrue(failure.getMessage().contains(
                    "cannot reorder inherited items"));
        }
    }

    private static BasicNodeProvider
    providerWithReferenceTypedAppendBase() {
        BasicNodeProvider provider = providerWithCanonicalItemType();
        String itemTypeBlueId = provider.getBlueIdByName(
                "Canonical list item");
        provider.addSingleDocs(
                "name: Reference typed append base\n"
                        + "type: List\n"
                        + "mergePolicy: append-only\n"
                        + "items:\n"
                        + "  - type:\n"
                        + "      blueId: " + itemTypeBlueId + "\n"
                        + "    marker: A\n"
                        + "  - type:\n"
                        + "      blueId: " + itemTypeBlueId + "\n"
                        + "    marker: B\n");
        return provider;
    }

    private static BasicNodeProvider
    providerWithInlineTypedPositionalBase() {
        BasicNodeProvider provider = providerWithCanonicalItemType();
        provider.addSingleDocs(
                "name: Inline typed positional base\n"
                        + "type: List\n"
                        + "items:\n"
                        + inlineTypedItem("A")
                        + inlineTypedItem("B"));
        return provider;
    }

    private static BasicNodeProvider providerWithCanonicalItemType() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Canonical list item\n"
                        + "type: Dictionary\n"
                        + "marker:\n"
                        + "  type: Text\n");
        return provider;
    }

    private static String inlineTypedItem(String marker) {
        return "  - type:\n"
                + "      name: Canonical list item\n"
                + "      type: Dictionary\n"
                + "      marker:\n"
                + "        type: Text\n"
                + "    marker: " + marker + "\n";
    }

    private static boolean hasListControls(List<Node> items) {
        return items != null && items.stream().anyMatch(
                item -> item.getPreviousBlueId() != null
                        || item.getPosition() != null);
    }
}
