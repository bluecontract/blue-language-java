package blue.language.snapshot;

import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResolvedReferenceCacheCompatibilityTest {

    @Test
    @SuppressWarnings("deprecation")
    void legacyDescriptorsRemainUsableWithoutPublishingVerificationEvidence() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode.ResolvedReferenceInterner interner = cache;
        Node firstSource = materialized("legacy-id", "first");
        Node secondSource = materialized("legacy-id", "second");

        FrozenNode first = FrozenNode.fromResolvedNode(firstSource, interner);
        FrozenNode second = FrozenNode.fromResolvedNode(secondSource, interner);

        assertSame(first, second, "the explicit legacy interner remains first-by-BlueId");
        assertSame(first, cache.lookup("legacy-id"));
        assertSame(first, cache.get("legacy-id").orElse(null));
        assertFalse(cache.getVerifiedCanonical("legacy-id").isPresent());
        assertFalse(cache.getVerifiedResolved("legacy-id").isPresent());
        assertEquals(1, cache.size());
    }

    @Test
    @SuppressWarnings("deprecation")
    void modernStructuralFreezeDoesNotCollapseContextualNodesByLegacyBlueId() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        FrozenNode first = cache.freezeResolved(materialized("shared-id", "first"));
        FrozenNode second = cache.freezeResolved(materialized("shared-id", "second"));

        assertNotSame(first, second);
        assertEquals("first", first.getProperties().get("payload").getValue());
        assertEquals("second", second.getProperties().get("payload").getValue());
        assertNull(cache.lookup("shared-id"),
                "modern structural freezing must not populate the legacy alias lane");
        assertFalse(cache.getVerifiedResolved("shared-id").isPresent(),
                "legacy aliases are never provider verification evidence");
    }

    @Test
    @SuppressWarnings("deprecation")
    void recursiveLegacyIndexAndMutableCopyRetainHistoricalBehavior() {
        FrozenNode child = FrozenNode.fromResolvedNode(materialized("nested-id", "value"));
        FrozenNode root = FrozenNode.fromResolvedNode(new Node().properties(
                "child", child.toNode()));
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        cache.indexResolved(root);
        Node copy = cache.mutableCopy("nested-id");

        assertEquals("value", copy.getProperties().get("payload").getValue());
        copy.getProperties().get("payload").value("changed");
        assertEquals("value", cache.mutableCopy("nested-id")
                .getProperties().get("payload").getValue());

        cache.clear();
        assertNull(cache.lookup("nested-id"));
        assertEquals(0, cache.size());
    }

    @Test
    @SuppressWarnings("deprecation")
    void disabledPolicyDoesNotRetainLegacyAliases() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache(BlueCachePolicy.disabled());
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                materialized("disabled-id", "value"));

        assertSame(candidate, cache.putIfAbsent("disabled-id", candidate));
        assertNull(cache.lookup("disabled-id"));
        assertEquals(0, cache.size());
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyAliasLaneRespectsConfiguredReferenceBounds() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache(
                BlueCachePolicy.builder().transientReferences(1, 1024L * 1024L).build());
        FrozenNode first = FrozenNode.fromResolvedNode(materialized("first-id", "first"));
        FrozenNode second = FrozenNode.fromResolvedNode(materialized("second-id", "second"));

        cache.putIfAbsent("first-id", first);
        cache.putIfAbsent("second-id", second);

        assertNull(cache.lookup("first-id"));
        assertSame(second, cache.lookup("second-id"));
        assertEquals(1, cache.size());
    }

    @Test
    void nullInternerCallRemainsSourceCompatibleAndSelectsStructuralPath() {
        FrozenNode frozen = FrozenNode.fromResolvedNode(new Node().value("value"), null);

        assertEquals("value", frozen.getValue());
        assertFalse(frozen.isStrictCanonical());
    }

    private static Node materialized(String blueId, String payload) {
        return new Node()
                .blueId(blueId)
                .properties("payload", new Node().value(payload));
    }
}
