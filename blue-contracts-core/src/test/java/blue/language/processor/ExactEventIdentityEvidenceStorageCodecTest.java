package blue.language.processor;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.ExactNodeStorageCodec;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.FrozenNodeStorageCodec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static blue.language.snapshot.ExactNodeStorageCodec.*;
import static org.junit.jupiter.api.Assertions.*;

final class ExactEventIdentityEvidenceStorageCodecTest {
    private static final int LIMIT = 1024 * 1024;
    private static final ExactEventIdentityEvidenceStorageCodec CODEC = new ExactEventIdentityEvidenceStorageCodec(LIMIT, 128);
    private static final FrozenNodeStorageCodec FROZEN = new FrozenNodeStorageCodec(LIMIT, 128);
    private static final ExactNodeStorageCodec ENVELOPE = new ExactNodeStorageCodec(LIMIT, 128);

    @Test void retainsStrictResolvedAndMixedInvocationAdmittedFrozenValues() {
        FrozenNode strict = FrozenNode.fromNode(new Node().properties("payload", new Node().value("source")));
        FrozenNode resolved = FrozenNode.fromResolvedNode(strict.toNode());
        FrozenNode mixed = resolved.withProperty("strictChild", strict);
        for (FrozenNode original : Arrays.asList(strict, resolved, mixed)) {
            String id = DirectBlueIdCalculator.calculateBlueId(original.toNode());
            ExactEventIdentityEvidence admitted = ExactEventIdentityEvidence.fromAdmitted(new ExactBlueValue(original, id));
            ExactEventIdentityEvidence restored = roundTrip(admitted);
            assertEquals(original.isStrictCanonical(), restored.frozenEvent().isStrictCanonical());
            if (original == mixed) {
                assertFalse(restored.frozenEvent().isStrictCanonical());
                assertTrue(restored.frozenEvent().at("/strictChild").isStrictCanonical());
            }
        }
    }

    @Test void retainsResolvedAnnotationsSchemaAndDetachedOwnership() {
        Node body = new Node().name("event").description("resolved metadata")
                .schema(new Schema().minLength(new Node().value(1).description("keyword metadata")))
                .properties("payload", new Node().value("value"));
        String id = DirectBlueIdCalculator.calculateBlueId(body);
        body.blueId(DirectBlueIdCalculator.calculateBlueId(new Node().value("annotation")));
        ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(null, body, id, null);
        ExactEventIdentityEvidence restored = roundTrip(evidence);
        Node detached = restored.event();
        detached.description("mutated");
        assertEquals("resolved metadata", restored.event().getDescription());
        assertNotNull(restored.event().getBlueId());
    }

    @Test void retainsPureReferencesAndAlreadyProvenCyclicMembersWithoutAnotherProof() {
        Node placeholder = new Node().properties("self", new Node().blueId("this#0"));
        String id = CircularSetIdentityCalculator.calculateCircularSetFinalization(Collections.singletonList(placeholder))
                .membersInInputOrder().get(0).finalBlueId();
        Node body = placeholder.clone(); body.getProperties().get("self").blueId(id);
        CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(Collections.singletonList(placeholder));
        roundTrip(ExactEventIdentityEvidence.verify(null, body, id, proof));
        roundTrip(ExactEventIdentityEvidence.verify(null, new Node().blueId(id), id, null));
        String ordinary = DirectBlueIdCalculator.calculateBlueId(new Node().value("ordinary"));
        roundTrip(ExactEventIdentityEvidence.verify(null, new Node().blueId(ordinary), ordinary, null));
        assertThrows(IllegalArgumentException.class, () -> ExactEventIdentityEvidence.verify(null, body, id, null));
        assertThrows(IllegalArgumentException.class, () -> ExactEventIdentityEvidence.verify(null,
                body.clone().properties("tampered", new Node().value(true)), id, proof));
    }

    @Test void malformedBoundsChecksumAndCanonicalFramingFailBeforeReturningEvidence() {
        Node body = new Node().properties("value", new Node().value("retained"));
        ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(null, body,
                DirectBlueIdCalculator.calculateBlueId(body), null);
        byte[] bytes = CODEC.encode(evidence), corrupt = bytes.clone(); corrupt[corrupt.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(corrupt));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ExactEventIdentityEvidenceStorageCodec(bytes.length - 1, 128).decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> new ExactEventIdentityEvidenceStorageCodec(bytes.length - 1, 128).encode(evidence));
        byte[] trailing = ENVELOPE.encodeEnvelope(ExactEventIdentityEvidenceStorageCodec.FORMAT, out -> {
            writeBytes(out, FROZEN.encode(evidence.frozenEvent())); writeText(out, evidence.eventBlueId()); out.writeByte(1);
        });
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(trailing));
        Node deep = new Node().properties("a", new Node().properties("b", new Node().value(true)));
        ExactEventIdentityEvidence nested = ExactEventIdentityEvidence.verify(null, deep, DirectBlueIdCalculator.calculateBlueId(deep), null);
        assertThrows(IllegalArgumentException.class, () -> new ExactEventIdentityEvidenceStorageCodec(LIMIT, 1).encode(nested));
        assertThrows(IllegalArgumentException.class, () -> new ExactEventIdentityEvidenceStorageCodec(LIMIT, 1).decode(CODEC.encode(nested)));
    }

    @Test void checksummedWrongReferenceOrCheapCanonicalIdentityIsRejected() {
        String first = DirectBlueIdCalculator.calculateBlueId(new Node().value("first"));
        String second = DirectBlueIdCalculator.calculateBlueId(new Node().value("second"));
        for (FrozenNode body : Arrays.asList(FrozenNode.fromNode(new Node().value("first")),
                FrozenNode.fromResolvedNode(new Node().blueId(first)))) {
            byte[] wrong = stored(body, second);
            assertThrows(IllegalArgumentException.class, () -> CODEC.decode(wrong));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decode(stored(body, "not-a-BlueId")));
        }
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(ENVELOPE.encodeEnvelope("old-format", out -> { })));
    }

    private static byte[] stored(FrozenNode body, String id) {
        return ENVELOPE.encodeEnvelope(ExactEventIdentityEvidenceStorageCodec.FORMAT, out -> {
            writeBytes(out, FROZEN.encode(body)); writeText(out, id);
        });
    }

    private static ExactEventIdentityEvidence roundTrip(ExactEventIdentityEvidence original) {
        byte[] bytes = CODEC.encode(original);
        ExactEventIdentityEvidence cold = new ExactEventIdentityEvidenceStorageCodec(LIMIT, 128).decode(bytes);
        assertNotSame(original, cold);
        assertEquals(original.eventBlueId(), cold.eventBlueId());
        assertArrayEquals(FROZEN.encode(original.frozenEvent()), FROZEN.encode(cold.frozenEvent()));
        assertArrayEquals(bytes, CODEC.encode(cold));
        Arrays.fill(bytes, (byte) 0);
        assertArrayEquals(CODEC.encode(original), CODEC.encode(cold));
        return cold;
    }
}
