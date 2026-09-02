package blue.language.merge;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.processor.TypeAssigner;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MixedBlueIdTypePositionTest {

    @Test
    void mixedBlueIdsInEveryTypePositionRemainInlineWithoutProviderAccess() {
        AtomicInteger providerCalls = new AtomicInteger();
        NodeProvider rejectingProvider = blueId -> {
            providerCalls.incrementAndGet();
            throw new AssertionError(
                    "Mixed type metadata must not fetch " + blueId);
        };
        Node declaredType = mixedType("Declared type");
        Node itemType = mixedType("Item type");
        Node keyType = mixedType("Key type");
        Node valueType = mixedType("Value type");
        Node source = new Node()
                .type(declaredType)
                .itemType(itemType)
                .keyType(keyType)
                .valueType(valueType);

        SnapshotResolution resolution = new Merger(
                new TypeMetadataCopyingProcessor(),
                rejectingProvider)
                .resolveSnapshot(source, ResolutionLimits.NO_LIMITS);

        assertEquals(0, providerCalls.get());
        Node resolved = resolution.resolvedRoot().toNode();
        assertMixedTypeRetained(resolved.getType(), declaredType);
        assertMixedTypeRetained(resolved.getItemType(), itemType);
        assertMixedTypeRetained(resolved.getKeyType(), keyType);
        assertMixedTypeRetained(resolved.getValueType(), valueType);

        Node canonical = resolution.canonicalRoot().toNode();
        assertCanonicalInlineIdentity(canonical.getType(), declaredType);
        assertCanonicalInlineIdentity(canonical.getItemType(), itemType);
        assertCanonicalInlineIdentity(canonical.getKeyType(), keyType);
        assertCanonicalInlineIdentity(canonical.getValueType(), valueType);
    }

    @Test
    void resolverProducedExpandedTypeCanBeResolvedAgainWithoutItsProvider() {
        Node canonicalType = new Node()
                .name("Provider type")
                .description("Exact semantic content");
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                canonicalType);
        AtomicInteger initialProviderCalls = new AtomicInteger();
        NodeProvider initialProvider = requestedBlueId -> {
            initialProviderCalls.incrementAndGet();
            assertEquals(typeBlueId, requestedBlueId);
            return Collections.singletonList(canonicalType.clone());
        };
        SnapshotResolution initial = new Merger(
                new TypeAssigner(),
                initialProvider)
                .resolveSnapshot(
                        new Node().type(new Node().blueId(typeBlueId)),
                        ResolutionLimits.NO_LIMITS);
        Node expanded = initial.resolvedRoot().toNode();

        assertEquals(1, initialProviderCalls.get());
        assertFalse(expanded.getType().isReferenceOnly());
        assertEquals(typeBlueId, expanded.getType().getBlueId());
        assertEquals("Provider type", expanded.getType().getName());

        AtomicInteger replayProviderCalls = new AtomicInteger();
        SnapshotResolution replayed = new Merger(
                new TypeAssigner(),
                requestedBlueId -> {
                    replayProviderCalls.incrementAndGet();
                    throw new AssertionError(
                            "Expanded type must not fetch "
                                    + requestedBlueId);
                })
                .resolveSnapshot(expanded, ResolutionLimits.NO_LIMITS);

        assertEquals(0, replayProviderCalls.get());
        assertEquals("Provider type",
                replayed.resolvedRoot().getType().getName());
        assertEquals(typeBlueId,
                replayed.canonicalRoot().getType().getReferenceBlueId());
    }

    private static Node mixedType(String name) {
        String unrelatedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Unrelated provenance for " + name));
        return new Node()
                .blueId(unrelatedBlueId)
                .name(name)
                .description("Authored semantic sibling");
    }

    private static void assertMixedTypeRetained(
            Node resolved,
            Node authored) {
        assertEquals(authored.getBlueId(), resolved.getBlueId());
        assertEquals(authored.getName(), resolved.getName());
        assertEquals(authored.getDescription(), resolved.getDescription());
    }

    private static void assertCanonicalInlineIdentity(
            Node canonicalReference,
            Node authored) {
        assertTrue(canonicalReference.isReferenceOnly());
        Node semanticBody = authored.clone().blueId(null);
        String expected = DirectBlueIdCalculator.calculateBlueId(
                semanticBody);
        assertEquals(expected, canonicalReference.getBlueId());
        assertNotEquals(authored.getBlueId(), canonicalReference.getBlueId());
    }

    private static final class TypeMetadataCopyingProcessor
            implements MergingProcessor {

        @Override
        public void process(
                Node target,
                Node source,
                NodeProvider nodeProvider,
                NodeResolver nodeResolver,
                CanonicalTypeIdentityLookup typeIdentities) {
            if (source.getType() != null) {
                target.type(source.getType());
            }
            if (source.getItemType() != null) {
                target.itemType(source.getItemType());
            }
            if (source.getKeyType() != null) {
                target.keyType(source.getKeyType());
            }
            if (source.getValueType() != null) {
                target.valueType(source.getValueType());
            }
        }
    }
}
