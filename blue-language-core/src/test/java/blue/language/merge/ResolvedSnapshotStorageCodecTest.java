package blue.language.merge;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ResolvedSnapshotStorageCodecTest {
    private final ResolvedSnapshotStorageCodec codec = new ResolvedSnapshotStorageCodec(4 * 1024 * 1024, 128);

    @Test void bodyPairReconstructionCannotReplaceRetainedResolverEvidence() {
        Node type = new Node().name("PaymentInstruction")
                .properties("reserved", new Node().value(true));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("PaymentInstruction");
        ResolvedSnapshot original;
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            original = language.snapshots().resolve(new Node().type(new Node().blueId(typeId)));
        }
        assertNotNull(original.verifiedReferenceResolution());
        assertEquals(typeId, original.canonicalTypeIdentities()
                .requireCanonicalTypeBlueId(original.resolvedRoot().getType()));

        ResolvedSnapshot bodyPair = new ResolvedSnapshot(original.canonicalRoot(),
                original.resolvedRoot(), original.blueId());
        assertEquals(original.blueId(), bodyPair.blueId());
        assertEquals(original.frozenResolvedRoot().resolvedStructuralKey(),
                bodyPair.frozenResolvedRoot().resolvedStructuralKey());
        assertNull(bodyPair.verifiedReferenceResolution());
        assertThrows(IllegalStateException.class, () -> bodyPair.canonicalTypeIdentities()
                .requireCanonicalTypeBlueId(bodyPair.resolvedRoot().getType()));

        // The reader has no provider and performs no resolution or processing.
        ResolvedSnapshot restored = codec.decode(codec.encode(original));
        assertEquals(typeId, restored.canonicalTypeIdentities()
                .requireCanonicalTypeBlueId(restored.resolvedRoot().getType()));
        assertNotNull(restored.verifiedReferenceResolution());
        assertLanes(original, restored);
    }

    @Test void restoresActualResolverSourceTypeEvidenceAndVerifiedProvenanceWithoutProviderReplay() {
        Node type = new Node().name("Parent").properties("inherited", new Node().value("parent value"));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Parent");
        ResolvedSnapshot original;
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            original = language.snapshots().resolve(new Node()
                    .type(new Node().blueId(typeId)).properties("local", new Node().value("local value")));
        }
        ResolvedSnapshot restored = codec.decode(codec.encode(original));
        assertLanes(original, restored);
        assertTrue(restored.isSourceBacked());
        assertTrue(restored.isResolutionComplete());
        assertEquals("parent value", restored.resolvedAt("/inherited").getValue());
        CanonicalTypeIdentityEvidence evidence = restored.canonicalTypeIdentities()
                .findCanonicalTypeIdentityEvidence(restored.resolvedRoot().getType(), restored.sourceRoot().getType()).get();
        assertEquals(typeId, evidence.blueId());
        assertTrue(evidence.hasReferenceSource());
        assertNotNull(original.verifiedReferenceResolution());
        assertEquals(original.verifiedReferenceResolution().requestedBlueId(),
                restored.verifiedReferenceResolution().requestedBlueId());
        assertEquals(original.verifiedReferenceResolution().canonicalRoot().resolvedStructuralKey(),
                restored.verifiedReferenceResolution().canonicalRoot().resolvedStructuralKey());
        assertEquals(original.verifiedReferenceResolution().resolvedRoot().resolvedStructuralKey(),
                restored.verifiedReferenceResolution().resolvedRoot().resolvedStructuralKey());
        assertEquals(typeId, restored.verifiedReferenceResolution().canonicalTypeIdentities()
                .requireCanonicalTypeBlueId(restored.resolvedRoot().getType()));
    }

    @Test void retainsNestedAuthoredInlineTypeProofAndSource() {
        ResolvedSnapshot snapshot;
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            snapshot = language.snapshots().resolve(new Node().type(new Node().name("Child")
                    .type(new Node().name("Parent").properties("inherited", new Node().value("value")))));
        }
        ResolvedSnapshot copy = codec.decode(codec.encode(snapshot));
        assertLanes(snapshot, copy);
        CanonicalTypeIdentityEvidence before = snapshot.canonicalTypeIdentities()
                .findCanonicalTypeIdentityEvidence(snapshot.resolvedRoot().getType(), snapshot.sourceRoot().getType()).get();
        CanonicalTypeIdentityEvidence after = copy.canonicalTypeIdentities()
                .findCanonicalTypeIdentityEvidence(copy.resolvedRoot().getType(), copy.sourceRoot().getType()).get();
        assertEquals(before.blueId(), after.blueId());
        assertEquals(FrozenNode.fromSourceNode(before.authoredTypeSource()).resolvedStructuralKey(),
                FrozenNode.fromSourceNode(after.authoredTypeSource()).resolvedStructuralKey());
        assertEquals(FrozenNode.fromNode(before.canonicalTypeIdentityInput()).resolvedStructuralKey(),
                FrozenNode.fromNode(after.canonicalTypeIdentityInput()).resolvedStructuralKey());
        assertFalse(after.authoredTypeSource().getType().isReferenceOnly());
        assertTrue(after.canonicalTypeIdentityInput().getType().isReferenceOnly());
    }

    @Test void retainsIncompleteDeferredAndCanonicalBackedDistinctions() {
        FrozenNode source = FrozenNode.fromSourceNode(new Node().name("deferred"));
        FrozenNode resolved = FrozenNode.fromResolvedNode(new Node().name("deferred"));
        ResolvedSnapshot deferred = ResolvedSnapshot.withDeferredSource(source, resolved, CanonicalTypeIdentityLookup.incomplete());
        ResolvedSnapshot copy = codec.decode(codec.encode(deferred));
        assertFalse(copy.isResolutionComplete()); assertFalse(copy.hasCanonicalIdentity()); assertTrue(copy.isSourceBacked());
        assertFalse(copy.canonicalTypeIdentities().hasCompleteCoverage());
        assertThrows(IllegalStateException.class, copy::blueId);
        FrozenNode canonical = FrozenNode.fromNode(new Node().name("canonical"));
        ResolvedSnapshot pair = new ResolvedSnapshot(canonical, FrozenNode.fromResolvedNode(canonical.toNode()));
        ResolvedSnapshot pairCopy = codec.decode(codec.encode(pair));
        assertLanes(pair, pairCopy); assertFalse(pairCopy.isSourceBacked()); assertTrue(pairCopy.isResolutionComplete());
        assertFalse(pairCopy.canonicalTypeIdentities().hasCompleteCoverage()); assertNull(pairCopy.verifiedReferenceResolution());
    }

    @Test void retainsEveryAmbiguousTypeBucketAndSourceDisambiguation() {
        Node shape = new Node().name("completed shape");
        Node authoredA = new Node().name("authored A"), authoredB = new Node().name("authored B");
        FrozenNode a = FrozenNode.fromNode(authoredA), b = FrozenNode.fromNode(authoredB);
        CanonicalTypeIdentityIndex index = new CanonicalTypeIdentityIndex();
        index.record(shape, a.blueId(), CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                a, FrozenNode.fromSourceNode(authoredA));
        index.record(shape.clone(), b.blueId(), CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                b, FrozenNode.fromSourceNode(authoredB));
        FrozenNode canonical = FrozenNode.fromNode(new Node().value("root"));
        ResolvedSnapshot original = ResolvedSnapshot.restoreStored(canonical, canonical,
                FrozenNode.fromResolvedNode(canonical.toNode()), ResolutionProvenance.none(), index.snapshot(), false, false);
        ResolvedSnapshot copy = codec.decode(codec.encode(original));
        assertEquals(a.blueId(), copy.canonicalTypeIdentities().requireCanonicalTypeBlueId(shape.clone(), authoredA));
        assertEquals(b.blueId(), copy.canonicalTypeIdentities().requireCanonicalTypeBlueId(shape.clone(), authoredB));
        assertThrows(IllegalStateException.class, () -> copy.canonicalTypeIdentities().requireCanonicalTypeBlueId(shape));
    }

    @Test void rejectsCorruptedSnapshotEnvelope() {
        FrozenNode root = FrozenNode.fromNode(new Node().value("root"));
        byte[] bytes = codec.encode(new ResolvedSnapshot(root, FrozenNode.fromResolvedNode(root.toNode())));
        byte[] corrupt = bytes.clone(); corrupt[corrupt.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(corrupt));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
    }

    private static void assertLanes(ResolvedSnapshot expected, ResolvedSnapshot actual) {
        assertEquals(expected.blueId(), actual.blueId());
        assertEquals(expected.frozenSourceRoot().resolvedStructuralKey(), actual.frozenSourceRoot().resolvedStructuralKey());
        assertEquals(expected.frozenCanonicalRoot().resolvedStructuralKey(), actual.frozenCanonicalRoot().resolvedStructuralKey());
        assertEquals(expected.frozenResolvedRoot().resolvedStructuralKey(), actual.frozenResolvedRoot().resolvedStructuralKey());
        assertEquals(expected.isResolutionComplete(), actual.isResolutionComplete());
        assertEquals(expected.isSourceBacked(), actual.isSourceBacked());
        assertEquals(expected.canonicalIndex().keySet(), actual.canonicalIndex().keySet());
        assertEquals(expected.resolvedIndex().keySet(), actual.resolvedIndex().keySet());
    }
}
