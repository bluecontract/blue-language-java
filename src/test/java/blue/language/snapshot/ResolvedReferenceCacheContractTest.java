package blue.language.snapshot;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedReferenceCacheContractTest {

    @Test
    void putVerifiedCanonicalRejectsReferenceOnlyNode() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        String referenceId = new Blue().calculateBlueId(new Node().value("referenced"));
        FrozenNode reference = FrozenNode.fromNode(new Node().blueId(referenceId));

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical(referenceId, reference));
    }

    @Test
    void putVerifiedResolvedRejectsReferenceOnlyCanonicalNode() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        Node content = new Node().value("value");
        String referenceId = new Blue().calculateBlueId(content);
        FrozenNode reference = FrozenNode.fromNode(new Node().blueId(referenceId));
        FrozenNode resolved = cache.freezeVerifiedResolved(referenceId, content);

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedResolved(referenceId, reference, resolved));
    }

    @Test
    void putVerifiedResolvedRejectsReferenceOnlyResolvedNode() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(canonicalNode);
        FrozenNode resolved = cache.freezeVerifiedResolved(blueId, new Node().blueId(blueId));

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedResolved(blueId, canonical, resolved));
    }

    @Test
    void putVerifiedCanonicalRejectsMismatchedBlueId() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("value"));

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical("wrong-id", canonical));
    }

    @Test
    void putVerifiedResolvedRejectsMismatchedBlueId() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(canonicalNode);
        FrozenNode resolved = cache.freezeVerifiedResolved("different-id", canonicalNode);

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedResolved(blueId, canonical, resolved));
    }

    @Test
    void conflictingCanonicalEntryForSameBlueIdFailsDeterministically() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(canonicalNode);
        FrozenNode forgedConflict = FrozenNode.fromUncheckedCanonicalNode(new Node().value("different"));

        cache.putVerifiedCanonical(blueId, canonical);

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedCanonical(blueId, forgedConflict));
    }

    @Test
    void uncheckedCanonicalNodeCannotEnterVerifiedCache() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        assertThrows(IllegalArgumentException.class, () -> cache.putVerifiedCanonical(
                blueId, FrozenNode.fromUncheckedCanonicalNode(canonicalNode)));
        assertFalse(cache.getVerifiedCanonical(blueId).isPresent());
    }

    @Test
    void contextualResolvedNodeCannotEnterVerifiedContentCache() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(canonicalNode);
        FrozenNode contextual = cache.freezeResolved(canonicalNode);

        assertThrows(IllegalArgumentException.class,
                () -> cache.putVerifiedResolved(blueId, canonical, contextual));
        assertFalse(cache.getVerifiedResolved(blueId).isPresent());
    }

    @Test
    void validVerifiedCanonicalAndResolvedContentAreReused() {
        Node canonicalNode = new Node().value("value");
        String blueId = new Blue().calculateBlueId(canonicalNode);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode canonical = FrozenNode.fromNode(canonicalNode);
        FrozenNode resolved = cache.freezeVerifiedResolved(blueId, canonicalNode);

        assertSame(canonical, cache.putVerifiedCanonical(blueId, canonical));
        assertSame(resolved, cache.putVerifiedResolved(blueId, canonical, resolved));
        assertSame(canonical, cache.getVerifiedCanonical(blueId).orElseThrow(AssertionError::new));
        assertSame(resolved, cache.getVerifiedResolved(blueId).orElseThrow(AssertionError::new));
        assertEquals(1, cache.size());
    }

    @Test
    void providerOrProcessorChangeClearsVerifiedEntries() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Type"));
        String typeId = provider.getBlueIdByName("Type");
        Blue blue = new Blue(provider);

        blue.resolve(new Node().type(new Node().blueId(typeId)));
        assertTrue(blue.resolvedReferenceCacheSize() > 0);

        blue.nodeProvider(new BasicNodeProvider());
        assertEquals(0, blue.resolvedReferenceCacheSize());

        blue.nodeProvider(provider);
        blue.resolve(new Node().type(new Node().blueId(typeId)));
        assertTrue(blue.resolvedReferenceCacheSize() > 0);

        blue.mergingProcessor(blue.getMergingProcessor());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }
}
