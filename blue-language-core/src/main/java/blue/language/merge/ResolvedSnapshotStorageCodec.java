package blue.language.merge;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.snapshot.ExactNodeStorageCodec;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.FrozenNodeStorageCodec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Complete Language-owned storage of resolver snapshots. Retains all three
 * input/runtime lanes, exact construction modes, type-evidence ambiguity
 * buckets and inline/reference origin, completeness and verified-reference
 * provenance including its independent nested type evidence.
 *
 * <p>This restores previously issued evidence from a host-authenticated
 * immutable object. Its checksum detects damage, not malicious substitution;
 * callers must validate the enclosing trusted object identity before decode.
 * It does not resolve content, call providers, or grant authority to untrusted
 * provider bodies. Operational bounds must abort publication on failure.
 * Derived path indexes and memoized hashes are rebuilt lazily.</p>
 */
public final class ResolvedSnapshotStorageCodec {
    /** Versioned physical format binding. */
    public static final String FORMAT = "blue-language/resolved-snapshot-storage/1";
    private static final String EVIDENCE_FORMAT = "blue-language/type-evidence-entry/1";
    private final ExactNodeStorageCodec nodes;
    private final FrozenNodeStorageCodec frozen;
    private final int maximumBytes;

    /**
     * Creates a complete snapshot codec with operational bounds.
     * @param maximumBytes maximum complete encoded bytes
     * @param maximumDepth maximum physical codec traversal depth from 1 through 256;
     *                     shared frozen subgraphs use non-traversing back references
     */
    public ResolvedSnapshotStorageCodec(int maximumBytes, int maximumDepth) {
        nodes = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
        frozen = new FrozenNodeStorageCodec(maximumBytes, maximumDepth);
        this.maximumBytes = maximumBytes;
    }

    /**
     * Encodes all lanes and complete Language-issued resolver evidence.
     * @param snapshot exact retained snapshot
     * @return complete checksummed bytes
     */
    public byte[] encode(ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return nodes.encodeEnvelope(FORMAT, out -> {
            writeFrozen(out, snapshot.frozenSourceRoot());
            writeFrozen(out, snapshot.hasCanonicalIdentity() ? snapshot.frozenCanonicalRoot() : null);
            writeFrozen(out, snapshot.frozenResolvedRoot());
            out.writeBoolean(snapshot.isResolutionComplete()); out.writeBoolean(snapshot.isSourceBacked());
            writeEvidence(out, snapshot.canonicalTypeIdentities());
            VerifiedReferenceResolution provenance = snapshot.verifiedReferenceResolution();
            out.writeBoolean(provenance != null);
            if (provenance != null) {
                writeText(out, provenance.requestedBlueId());
                writeFrozen(out, provenance.canonicalRoot()); writeFrozen(out, provenance.resolvedRoot());
                writeEvidence(out, provenance.canonicalTypeIdentities());
            }
        });
    }

    /**
     * Restores previously issued evidence from host-authenticated storage.
     * @param bytes complete bytes authenticated by the enclosing object store
     * @return exact immutable snapshot with its original authority boundaries
     */
    public ResolvedSnapshot decode(byte[] bytes) {
        ResolvedSnapshot result = nodes.decodeEnvelope(bytes, FORMAT, in -> {
            FrozenNode source = readFrozen(in), canonical = readFrozen(in), resolved = readFrozen(in);
            boolean complete = readBoolean(in), sourceBacked = readBoolean(in);
            CanonicalTypeIdentityLookup evidence = readEvidence(in);
            ResolutionProvenance provenance = ResolutionProvenance.none();
            if (readBoolean(in)) {
                String requested = requiredText(in);
                FrozenNode verifiedCanonical = Objects.requireNonNull(readFrozen(in), "verified canonical root");
                FrozenNode verifiedResolved = Objects.requireNonNull(readFrozen(in), "verified resolved root");
                CanonicalTypeIdentityLookup nested = readEvidence(in);
                if (!(nested instanceof CanonicalTypeIdentityIndex.EvidenceSnapshot))
                    throw invalid("Verified reference lacks resolver-owned evidence");
                provenance = ResolutionProvenance.verified(new VerifiedReferenceResolution(requested,
                        verifiedCanonical, verifiedResolved, (CanonicalTypeIdentityIndex.EvidenceSnapshot) nested));
            }
            return ResolvedSnapshot.restoreStored(source, canonical, resolved, provenance, evidence, complete, sourceBacked);
        });
        if (!Arrays.equals(bytes, encode(result))) throw invalid("Noncanonical snapshot storage");
        return result;
    }

    private void writeEvidence(DataOutputStream out, CanonicalTypeIdentityLookup lookup) throws IOException {
        if (lookup == CanonicalTypeIdentityLookup.incomplete()) { out.writeByte(0); return; }
        CanonicalTypeIdentityIndex.EvidenceSnapshot snapshot;
        if (lookup instanceof CanonicalTypeIdentityIndex.EvidenceSnapshot)
            snapshot = (CanonicalTypeIdentityIndex.EvidenceSnapshot) lookup;
        else if (lookup instanceof CanonicalTypeIdentityIndex)
            snapshot = ((CanonicalTypeIdentityIndex) lookup).snapshot();
        else throw invalid("Custom canonical type lookup has no complete storage export: " + lookup.getClass().getName());
        out.writeByte(1); out.writeBoolean(snapshot.hasCompleteCoverage());
        List<byte[]> entries = new ArrayList<>();
        long retainedBytes = 0;
        for (Map.Entry<SemanticTypeEvidenceKey, Map<String, CanonicalTypeIdentityIndex.Evidence>> bucket
                : snapshot.storageEntries().entrySet()) {
            for (CanonicalTypeIdentityIndex.Evidence evidence : bucket.getValue().values()) {
                byte[] encoded = nodes.encodeEnvelope(EVIDENCE_FORMAT, entry -> {
                    writeBytes(entry, nodes.encode(bucket.getKey().storageNode()));
                    writeText(entry, evidence.blueId()); writeText(entry, evidence.kind().name());
                    writeFrozen(entry, evidence.storageCanonicalInput()); writeFrozen(entry, evidence.storageAuthoredSource());
                });
                retainedBytes += encoded.length + Integer.BYTES;
                if (retainedBytes > maximumBytes) throw invalid("Type evidence storage byte bound exceeded");
                entries.add(encoded);
            }
        }
        entries.sort(ResolvedSnapshotStorageCodec::compareBytes);
        out.writeInt(entries.size());
        for (byte[] entry : entries) writeBytes(out, entry);
    }

    private CanonicalTypeIdentityLookup readEvidence(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        if (tag == 0) return CanonicalTypeIdentityLookup.incomplete();
        if (tag != 1) throw invalid("Unknown canonical evidence kind");
        boolean complete = readBoolean(in);
        int count = count(in, false);
        Map<SemanticTypeEvidenceKey, Map<String, CanonicalTypeIdentityIndex.Evidence>> buckets = new HashMap<>();
        for (int i = 0; i < count; i++) {
            nodes.decodeEnvelope(readBytes(in), EVIDENCE_FORMAT, entry -> {
                SemanticTypeEvidenceKey key = SemanticTypeEvidenceKey.of(nodes.decode(readBytes(entry)));
                String id = requiredText(entry);
                CanonicalTypeIdentityIndex.Evidence evidence = new CanonicalTypeIdentityIndex.Evidence(id,
                        CanonicalTypeIdentityIndex.EvidenceKind.valueOf(requiredText(entry)), readFrozen(entry), readFrozen(entry));
                evidence.descriptor(); // Existing identity/source validation; does not consult a provider.
                Map<String, CanonicalTypeIdentityIndex.Evidence> bucket = buckets.get(key);
                if (bucket == null) { bucket = new HashMap<>(); buckets.put(key, bucket); }
                if (bucket.put(id, evidence) != null) throw invalid("Duplicate canonical type evidence");
                return evidence;
            });
        }
        return new CanonicalTypeIdentityIndex.EvidenceSnapshot(buckets, complete);
    }

    private void writeFrozen(DataOutputStream out, FrozenNode value) throws IOException {
        out.writeBoolean(value != null); if (value != null) writeBytes(out, frozen.encode(value));
    }
    private FrozenNode readFrozen(DataInputStream in) throws IOException {
        return readBoolean(in) ? frozen.decode(readBytes(in)) : null;
    }
    private static int compareBytes(byte[] left, byte[] right) {
        int size = Math.min(left.length, right.length);
        for (int i = 0; i < size; i++) {
            int result = Integer.compare(left[i] & 255, right[i] & 255);
            if (result != 0) return result;
        }
        return Integer.compare(left.length, right.length);
    }
}
