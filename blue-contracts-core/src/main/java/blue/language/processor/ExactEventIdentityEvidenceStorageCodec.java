package blue.language.processor;

import blue.language.snapshot.ExactNodeStorageCodec;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.FrozenNodeStorageCodec;

import java.util.Arrays;
import java.util.Objects;

import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Physical retention of an already-issued exact event capability. The original
 * immutable construction and admitted BlueId are carried together without Source
 * canonicalization, provider lookup, BEX execution, or a new semantic admission.
 *
 * <p>Decode only bytes selected through an authenticated pinned host record.
 * A checksum detects corruption; it does not prove event identity or BEX
 * execution. This is not an external-input admission API. Untrusted events must
 * still use {@link ExactEventIdentityEvidence#verify} with their required proof.
 * Operational bounds and malformed bytes fail before any capability is returned.</p>
 */
public final class ExactEventIdentityEvidenceStorageCodec {
    /** Versioned physical binding, not a semantic identity. */
    public static final String FORMAT = "blue-contracts/exact-event-identity-evidence-storage/1";
    private final ExactNodeStorageCodec envelope;
    private final FrozenNodeStorageCodec frozen;

    /**
     * Creates a bounded physical codec.
     * @param maximumBytes maximum complete encoded bytes, at least 128
     * @param maximumDepth maximum physical frozen traversal depth, 1..256
     */
    public ExactEventIdentityEvidenceStorageCodec(int maximumBytes, int maximumDepth) {
        envelope = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
        frozen = new FrozenNodeStorageCodec(maximumBytes, maximumDepth);
    }

    /**
     * Retains original invocation-issued evidence without consulting providers.
     * @param evidence already-issued exact event evidence
     * @return newly owned checksummed bytes
     */
    public byte[] encode(ExactEventIdentityEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return envelope.encodeEnvelope(FORMAT, out -> {
            writeBytes(out, frozen.encode(evidence.frozenEvent()));
            writeText(out, evidence.eventBlueId());
        });
    }

    /**
     * Restores the original capability from authenticated pinned storage bytes.
     * No provider, runtime or handler is consulted. This does not independently
     * authenticate a resolved Source or cyclic member supplied by an external user.
     * @param bytes complete bytes authenticated by their owning host record
     * @return exact immutable event evidence in its original construction mode
     */
    public ExactEventIdentityEvidence decode(byte[] bytes) {
        ExactEventIdentityEvidence result = envelope.decodeEnvelope(bytes, FORMAT, in -> {
            FrozenNode event = frozen.decode(readBytes(in));
            return ExactEventIdentityEvidence.fromTrustedStorage(event, requiredText(in));
        });
        if (!Arrays.equals(bytes, encode(result))) throw invalid("Noncanonical exact event evidence storage");
        return result;
    }
}
