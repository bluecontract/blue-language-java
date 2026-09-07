package blue.language.resolve;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinimizedOverlayCanonicalIdentityParityTest {

    @Test
    void completedAndReferenceItemTypesCompareByCanonicalEvidence() {
        Node canonicalItemType = new Node()
                .name("Canonical item type");
        String itemTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                canonicalItemType);
        Node inheritedItem = item(
                "A",
                new Node().blueId(itemTypeBlueId));
        Node completedItem = item(
                "A",
                canonicalItemType.clone());
        Node inheritedListType = new Node()
                .mergePolicy("append-only")
                .items(Collections.singletonList(inheritedItem));
        Node resolved = new Node()
                .type(inheritedListType)
                .mergePolicy("append-only")
                .items(Arrays.asList(
                        completedItem,
                        new Node().value("B")));
        CanonicalTypeIdentityLookup identities = canonicalLookup(
                canonicalItemType,
                itemTypeBlueId);

        Node minimized = new MinimizedOverlayBuilder().build(
                FrozenNode.fromResolvedNode(resolved),
                identities);

        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        Collections.singletonList(inheritedItem)),
                minimized.getItems().get(0).getPreviousBlueId());
        assertFalse(minimized.getItems().stream().skip(1).anyMatch(
                item -> item.getPosition() != null
                        || item.getPreviousBlueId() != null));
    }

    @Test
    void minimizationRejectsMissingCanonicalTypeEvidence() {
        Node parentDefinition = new Node().name("Unproven parent type");
        Node completedParent = parentDefinition.clone().blueId(
                DirectBlueIdCalculator.calculateBlueId(
                        parentDefinition));
        Node resolved = new Node()
                .type(new Node()
                        .name("Unproven inline type")
                        .type(completedParent));

        assertThrows(
                IllegalStateException.class,
                () -> new MinimizedOverlayBuilder().build(
                        FrozenNode.fromResolvedNode(resolved),
                        CanonicalTypeIdentityLookup.incomplete()));
    }

    @Test
    void sharedObjectIdentityCannotBypassCanonicalTypeEvidence() {
        Node unprovenNestedType = new Node().name("Unproven nested type");
        Node sharedChild = new Node().type(unprovenNestedType);
        Node sharedParentType = new Node()
                .name("Shared parent type")
                .properties("child", sharedChild);
        Node resolved = new Node()
                .type(sharedParentType)
                .properties("child", sharedChild);

        assertThrows(
                IllegalStateException.class,
                () -> new MinimizedOverlayReconstructor(
                        CanonicalTypeIdentityLookup.incomplete())
                        .reconstruct(resolved));
    }

    private static Node item(String marker, Node type) {
        return new Node()
                .type(type)
                .properties("marker", new Node().value(marker));
    }

    private static CanonicalTypeIdentityLookup canonicalLookup(
            Node completedType,
            String canonicalBlueId) {
        FrozenNode completed = FrozenNode.fromResolvedNode(
                completedType);
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node candidate) {
                String blueId = requireCanonicalTypeBlueId(candidate);
                return Optional.of(candidate.isReferenceOnly()
                        ? CanonicalTypeIdentityEvidence.referenceSource(blueId)
                        : CanonicalTypeIdentityEvidence.identityOnly(blueId));
            }

            @Override
            public String requireCanonicalTypeBlueId(Node candidate) {
                if (candidate.isReferenceOnly()
                        && canonicalBlueId.equals(
                                candidate.getBlueId())) {
                    return canonicalBlueId;
                }
                if (!completed.sameResolvedStructure(
                        FrozenNode.fromResolvedNode(candidate))) {
                    throw new IllegalStateException(
                            "Unexpected completed type structure");
                }
                return canonicalBlueId;
            }
        };
    }
}
