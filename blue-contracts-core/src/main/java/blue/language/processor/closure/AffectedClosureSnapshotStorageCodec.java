package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.ExactNodeStorageCodec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Complete physical transport for a verified affected-closure snapshot, including
 * the original exact snapshots behind immutable rooted historical witnesses.
 *
 * <p>Input bytes must come from the caller's authenticated, pinned storage root.
 * The checksum detects corruption; it does not grant publication, history or
 * prospective-birth authority. This codec restores snapshots, not processor
 * results, pending invocations or a durable SDK. It never executes processing.</p>
 *
 * <p>All exact Node fields and complete cyclic proofs are retained. Shared original
 * snapshots use backward references. Byte and physical traversal-depth limits are
 * operational storage limits, not semantic graph, gas or processing limits.</p>
 *
 * <p>An instance can retain up to 32 previously accepted envelopes and
 * {@code min(maximumBytes, 8 MiB)} of their bytes. Exact hits parse fresh objects
 * without repeating codec-level semantic verification or canonical re-encoding.
 * Acceptance follows complete decoding or complete encoding of an already
 * library-verified, privately owned snapshot and its owned witness graph.
 * Constructors still validate normally. These additional instance-lifetime
 * payload bytes are not a total-heap bound or current publication authority.</p>
 */
public final class AffectedClosureSnapshotStorageCodec {
    /** Versioned physical format, independent of semantic identities. */
    public static final String FORMAT = "blue-contracts/affected-closure-snapshot-storage/1";
    private final ExactNodeStorageCodec nodes;
    private final int maximumBytes;
    private final int reuseBytes;
    private final AcceptedBytes acceptedBytes;
    private final Runnable beforeFullVerification;

    /**
     * Creates a complete snapshot codec with operational bounds.
     * @param maximumBytes maximum complete encoded envelope size
     * @param maximumDepth maximum physical traversal depth, from 1 through 256
     */
    public AffectedClosureSnapshotStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, Math.min(maximumBytes, 8 * 1024 * 1024), 32);
    }

    AffectedClosureSnapshotStorageCodec(int maximumBytes, int maximumDepth, int certificateBytes, int certificateEntries) {
        this(maximumBytes, maximumDepth, certificateBytes, certificateEntries, null);
    }

    AffectedClosureSnapshotStorageCodec(int maximumBytes, int maximumDepth, int certificateBytes, int certificateEntries,
            Runnable beforeFullVerification) {
        nodes = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
        this.maximumBytes = maximumBytes;
        reuseBytes = Math.min(maximumBytes, 8 * 1024 * 1024);
        if (certificateBytes < 0 || certificateBytes > reuseBytes || certificateEntries < 0 || certificateEntries > 32
                || (certificateBytes == 0) != (certificateEntries == 0))
            throw new IllegalArgumentException("Invalid accepted snapshot byte bounds");
        acceptedBytes = new AcceptedBytes(certificateBytes, certificateEntries);
        this.beforeFullVerification = beforeFullVerification;
    }

    /**
     * Encodes a complete, independently verified snapshot and its witness DAG.
     * @param snapshot exact snapshot selected from an authenticated publication
     * @return newly owned canonical checksummed bytes
     */
    public byte[] encode(AffectedClosureSnapshot snapshot) {
        try (SnapshotStorageCall call = newCall()) { return encodeInCall(snapshot, call); }
    }

    SnapshotStorageCall newCall() { return new SnapshotStorageCall(this, reuseBytes, 32); }

    byte[] encodeInCall(AffectedClosureSnapshot snapshot, SnapshotStorageCall call) {
        return encodeInCall(snapshot, call, null);
    }

    private byte[] encodeInCall(AffectedClosureSnapshot snapshot, SnapshotStorageCall call, DecodedRecords records) {
        Objects.requireNonNull(snapshot, "snapshot");
        byte[] encoded = nodes.encodeEnvelope(FORMAT, out -> writeSnapshot(out, snapshot, 0,
                new IdentityHashMap<AffectedClosureSnapshot, Integer>(),
                new IdentityHashMap<AffectedClosureSnapshot, Boolean>(), call, records));
        // A completed canonical encoder may retain these exact bytes only when
        // the source already has library-issued pure-state proof and every
        // emitted representation (including witness originals) is detached.
        // writeSnapshot above still validates every otherwise-unverified witness.
        // Public/freshly mutable input and an incomplete encode never qualify.
        if (snapshot.hasVerifiedOwnedState() && snapshot.hasDetachedRepresentation()) {
            acceptedBytes.retainEncoded(encoded);
        }
        return encoded;
    }

    /**
     * Restores every exact snapshot and witness role, reusing only previously
     * decode-accepted exact bytes while constructing a fresh object graph.
     * @param bytes complete bytes selected through an authenticated storage root
     * @return detached immutable snapshot with complete historical provenance
     * @throws IllegalArgumentException for corrupt, noncanonical or over-bound bytes
     */
    public AffectedClosureSnapshot decode(byte[] bytes) {
        try (SnapshotStorageCall call = newCall()) { return decodeInCall(bytes, call); }
    }

    AffectedClosureSnapshot decodeInCall(byte[] bytes, SnapshotStorageCall call) {
        return decodeInCall(bytes, call, null);
    }

    // Package-only deterministic handoff control; it never receives retained bytes or graphs.
    AffectedClosureSnapshot decodeInCall(byte[] bytes, SnapshotStorageCall call, Runnable afterSelection) {
        return decodeEnvelopeInCall(bytes, call, afterSelection).snapshot;
    }

    // Keeps the call-local memo bound to the bytes actually parsed, not a later
    // copy of a concurrently mutable caller array.
    DecodedSnapshot decodeEnvelopeInCall(byte[] bytes, SnapshotStorageCall call, Runnable afterSelection) {
        // Check the complete envelope bound BEFORE allocating the caller's detached copy.
        if (bytes == null || bytes.length < 36 || bytes.length > maximumBytes)
            throw invalid("Missing, truncated or oversized exact storage");
        boolean reuse = call.reuseEnabled();
        byte[] prior = reuse ? acceptedBytes.find(bytes) : null;
        boolean accepted = prior != null;
        byte[] selected = accepted ? prior : bytes.clone();
        if (!accepted) acceptedBytes.fullDecode();
        if (afterSelection != null) afterSelection.run();
        // Each envelope keeps its own fresh alias table. Only these exact private
        // bytes can omit the pure codec checks already completed by a prior decode.
        DecodedRecords records = new DecodedRecords();
        AffectedClosureSnapshot result = nodes.decodeEnvelope(selected, FORMAT,
                in -> readSnapshot(in, 0, records, call, accepted));
        if (!accepted) {
            if (!Arrays.equals(selected, encodeInCall(result, call, records))) throw invalid("Noncanonical affected-closure snapshot storage");
            if (reuse) acceptedBytes.retain(selected);
        }
        DecodedSnapshot decoded = new DecodedSnapshot(result, selected, records.completed);
        // Every completed record is an owned snapshot independently verified on
        // the cold path, or reconstructed from this codec's accepted exact bytes.
        // Issue only after whole-envelope acceptance, never to derived role views.
        for (AffectedClosureSnapshot value : records.completed) value.acceptStorageVerification(decoded);
        return decoded;
    }

    /**
     * Private to one bounded envelope parse, including its canonical comparison.
     * The existing backward-reference table already retains every completed record;
     * this identity index adds no graph ownership or authority beyond that parse.
     * No persistent snapshot certificate is issued until the whole envelope succeeds.
     */
    static final class DecodedRecords {
        private final List<AffectedClosureSnapshot> completed = new ArrayList<AffectedClosureSnapshot>();
        private final IdentityHashMap<AffectedClosureSnapshot, Boolean> verified =
                new IdentityHashMap<AffectedClosureSnapshot, Boolean>();
        private DecodedRecords() { }
        private void complete(AffectedClosureSnapshot snapshot) {
            completed.add(snapshot); verified.put(snapshot, Boolean.TRUE);
        }
        void requireVerified(AffectedClosureSnapshot snapshot) {
            if (!verified.containsKey(snapshot)) throw invalid("Witness is not a completed owned snapshot record");
        }
    }

    static final class DecodedSnapshot {
        final AffectedClosureSnapshot snapshot;
        final byte[] bytes;
        private final IdentityHashMap<AffectedClosureSnapshot, Boolean> verified =
                new IdentityHashMap<AffectedClosureSnapshot, Boolean>();
        // Constructed only after a complete owned decode of verified canonical bytes.
        private DecodedSnapshot(AffectedClosureSnapshot snapshot, byte[] bytes,
                List<AffectedClosureSnapshot> completed) {
            this.snapshot = snapshot; this.bytes = bytes;
            for (AffectedClosureSnapshot value : completed) verified.put(value, Boolean.TRUE);
        }
        boolean certifies(AffectedClosureSnapshot selected) { return verified.containsKey(selected); }
    }

    AcceptedByteStatistics acceptedByteStatistics() { return acceptedBytes.statistics(); }

    private void writeSnapshot(DataOutputStream out, AffectedClosureSnapshot value, int depth,
            IdentityHashMap<AffectedClosureSnapshot, Integer> completed,
            IdentityHashMap<AffectedClosureSnapshot, Boolean> active, SnapshotStorageCall call, DecodedRecords records) throws IOException {
        nodes.depth(depth);
        Integer prior = completed.get(value);
        if (prior != null) { out.writeByte(0); out.writeInt(prior); return; }
        if (active.put(value, Boolean.TRUE) != null) throw invalid("Cyclic snapshot storage references");
        if (records == null) call.verify(value);
        else records.requireVerified(value);
        out.writeByte(1);
        writeText(out, value.closureIdentity()); out.writeLong(value.graphGeneration());
        out.writeInt(value.managedDocuments().size());
        for (ManagedDocumentSnapshot document : value.managedDocuments()) {
            writeText(out, document.documentId().value()); writeText(out, document.blueId());
            nodes.writeNode(out, document.document(), depth + 1);
            out.writeBoolean(document.initialized()); out.writeBoolean(document.terminated());
            out.writeBoolean(document.publicRoot()); out.writeLong(document.epoch());
            out.writeLong(document.componentGeneration());
        }
        out.writeInt(value.occurrences().size());
        for (ManagedOccurrenceBinding row : value.occurrences()) writeOccurrence(out, row);
        writeText(out, value.occurrenceBindingSetIdentity());
        out.writeInt(value.components().size());
        for (ComponentSnapshot component : value.components()) writeComponent(out, component, depth + 1);
        out.writeInt(value.publicRootDocumentIds().size());
        for (DocumentId root : value.publicRootDocumentIds()) writeText(out, root.value());
        RootedWitnessFrame.State witnesses = value.rootedWitnesses();
        out.writeBoolean(witnesses != null);
        if (witnesses != null) {
            Map<DocumentId, AffectedClosureSnapshot> originals = witnesses.storedOriginals();
            List<DocumentId> sources = new ArrayList<DocumentId>(originals.keySet());
            Collections.sort(sources);
            out.writeInt(sources.size());
            for (DocumentId source : sources) {
                writeText(out, source.value());
                writeSnapshot(out, originals.get(source), depth + 1, completed, active, call, records);
            }
        }
        active.remove(value);
        completed.put(value, completed.size());
    }

    private AffectedClosureSnapshot readSnapshot(DataInputStream in, int depth,
            DecodedRecords records, SnapshotStorageCall call, boolean accepted) throws IOException {
        nodes.depth(depth);
        int tag = in.readUnsignedByte();
        if (tag == 0) {
            int index = in.readInt();
            if (index < 0 || index >= records.completed.size()) throw invalid("Invalid snapshot backward reference");
            return records.completed.get(index);
        }
        if (tag != 1) throw invalid("Unknown snapshot storage tag");
        String identity = requiredText(in); long generation = in.readLong();
        List<ManagedDocumentSnapshot> documents = new ArrayList<ManagedDocumentSnapshot>();
        for (int remaining = count(in, false); remaining > 0; remaining--) {
            DocumentId documentId = documentId(in); String blueId = requiredText(in);
            Node body = nodes.readNode(in, depth + 1);
            documents.add(new ManagedDocumentSnapshot(documentId, blueId, body,
                    readBoolean(in), readBoolean(in), readBoolean(in), in.readLong(), in.readLong()));
        }
        List<ManagedOccurrenceBinding> rows = new ArrayList<ManagedOccurrenceBinding>();
        for (int remaining = count(in, false); remaining > 0; remaining--) rows.add(readOccurrence(in));
        String rowsIdentity = requiredText(in);
        List<ComponentSnapshot> components = new ArrayList<ComponentSnapshot>();
        for (int remaining = count(in, false); remaining > 0; remaining--) components.add(readComponent(in, depth + 1));
        List<DocumentId> publicRoots = new ArrayList<DocumentId>();
        for (int remaining = count(in, false); remaining > 0; remaining--) publicRoots.add(documentId(in));
        RootedWitnessFrame.State witnesses = null;
        if (readBoolean(in)) {
            Map<DocumentId, AffectedClosureSnapshot> originals = new LinkedHashMap<DocumentId, AffectedClosureSnapshot>();
            for (int remaining = count(in, false); remaining > 0; remaining--) {
                DocumentId source = documentId(in);
                if (originals.put(source, readSnapshot(in, depth + 1, records, call, accepted)) != null)
                    throw invalid("Duplicate stored witness source");
            }
            witnesses = RootedWitnessFrame.State.fromStoredOriginals(originals, records);
        }
        AffectedClosureSnapshot result = new AffectedClosureSnapshot(identity, generation, documents,
                rows, rowsIdentity, components, publicRoots, witnesses);
        if (!accepted) call.verify(result);
        // Cold records completed their ordinary pure/identity verification above.
        // Warm records come only from this codec's privately accepted exact bytes.
        records.complete(result);
        return result;
    }

    /** Immutable, bounded counters only; no input identities, bytes or decoded graphs escape. */
    static final class AcceptedByteStatistics {
        final long hits, fullDecodeAttempts;
        final int retainedBytes, retainedEntries, peakBytes, peakEntries;
        private AcceptedByteStatistics(long hits, long fullDecodeAttempts, int retainedBytes,
                int retainedEntries, int peakBytes, int peakEntries) {
            this.hits = hits; this.fullDecodeAttempts = fullDecodeAttempts; this.retainedBytes = retainedBytes;
            this.retainedEntries = retainedEntries; this.peakBytes = peakBytes; this.peakEntries = peakEntries;
        }
    }

    /** Codec-lifetime certificates only: never decoded-object interning or current publication authority. */
    private static final class AcceptedBytes {
        private final int maximumBytes, maximumEntries;
        private final List<byte[]> entries = new ArrayList<byte[]>();
        private int retainedBytes, peakBytes, peakEntries;
        private long hits, fullDecodeAttempts;

        private AcceptedBytes(int maximumBytes, int maximumEntries) {
            this.maximumBytes = maximumBytes; this.maximumEntries = maximumEntries;
        }

        private synchronized byte[] find(byte[] bytes) {
            for (int index = 0; index < entries.size(); index++) {
                byte[] entry = entries.get(index);
                if (Arrays.equals(entry, bytes)) {
                    entries.add(entries.remove(index));
                    if (hits != Long.MAX_VALUE) hits++;
                    return entry;
                }
            }
            return null;
        }

        private synchronized void fullDecode() {
            if (fullDecodeAttempts != Long.MAX_VALUE) fullDecodeAttempts++;
        }

        // The argument is the private owned array that just completed full validation.
        // No caller can mutate it. Concurrent misses may accept the same bytes once.
        private synchronized void retain(byte[] bytes) {
            if (maximumEntries == 0 || bytes.length > maximumBytes) return;
            for (int index = 0; index < entries.size(); index++) {
                if (Arrays.equals(entries.get(index), bytes)) {
                    entries.add(entries.remove(index));
                    return;
                }
            }
            while (entries.size() >= maximumEntries || retainedBytes > maximumBytes - bytes.length)
                retainedBytes -= entries.remove(0).length;
            entries.add(bytes); retainedBytes += bytes.length;
            peakBytes = Math.max(peakBytes, retainedBytes); peakEntries = Math.max(peakEntries, entries.size());
        }

        // The encoder returns its frame to callers. Bound before allocating an
        // independent private copy; never retain the exposed returned byte array.
        private synchronized void retainEncoded(byte[] bytes) {
            if (maximumEntries == 0 || bytes.length > maximumBytes) return;
            for (int index = 0; index < entries.size(); index++) {
                if (Arrays.equals(entries.get(index), bytes)) {
                    entries.add(entries.remove(index));
                    return;
                }
            }
            retain(bytes.clone());
        }

        private synchronized AcceptedByteStatistics statistics() {
            return new AcceptedByteStatistics(hits, fullDecodeAttempts, retainedBytes, entries.size(), peakBytes, peakEntries);
        }
    }

    private static void writeOccurrence(DataOutputStream out, ManagedOccurrenceBinding row) throws IOException {
        writeText(out, row.occurrenceIdentity()); writeText(out, row.bindingIdentity());
        writeText(out, row.bindingPolicyIdentity()); writeText(out, row.sourceDocumentId().value());
        writeText(out, row.sourcePath()); out.writeLong(row.activationGeneration());
        writeText(out, row.targetDocumentId().value()); writeText(out, row.expectedTargetBlueId());
        out.writeBoolean(row.active()); out.writeBoolean(row.pendingHistoricalEpoch() != null);
        if (row.pendingHistoricalEpoch() != null) out.writeLong(row.pendingHistoricalEpoch());
        ManagedRepresentationCursor cursor = row.pendingRepresentationCursor();
        out.writeBoolean(cursor != null);
        if (cursor != null) {
            writeText(out, cursor.anchorReceiptIdentity()); writeText(out, cursor.positionIdentity());
            writeText(out, cursor.targetPositionIdentity()); writeText(out, cursor.nextRevisionReceiptIdentity());
        }
    }

    private static ManagedOccurrenceBinding readOccurrence(DataInputStream in) throws IOException {
        String occurrence = requiredText(in), binding = requiredText(in), policy = requiredText(in);
        DocumentId source = documentId(in); ScopeAddress address = ScopeAddress.embedded(requiredText(in), in.readLong());
        DocumentId target = documentId(in); String expected = requiredText(in); boolean active = readBoolean(in);
        Long pending = readBoolean(in) ? Long.valueOf(in.readLong()) : null;
        ManagedOccurrenceBinding result = ManagedOccurrenceBinding.verified(occurrence, binding, policy,
                source, address, target, expected, active, pending);
        if (readBoolean(in)) result = result.withRepresentationCursor(new ManagedRepresentationCursor(
                requiredText(in), requiredText(in), requiredText(in), readText(in)));
        return result;
    }

    private void writeComponent(DataOutputStream out, ComponentSnapshot value, int depth) throws IOException {
        writeText(out, value.componentIdentity()); writeText(out, value.componentStateIdentity());
        out.writeLong(value.componentGeneration()); writeText(out, value.kind().name());
        out.writeInt(value.orderedMemberDocumentIds().size());
        for (DocumentId document : value.orderedMemberDocumentIds()) writeText(out, document.value());
        out.writeInt(value.orderedMemberBlueIds().size());
        for (String blueId : value.orderedMemberBlueIds()) writeText(out, blueId);
        writeText(out, value.masterBlueId()); writeText(out, value.cyclicProofIdentity());
        CyclicSetProof proof = value.completeCyclicProof();
        out.writeBoolean(proof != null);
        if (proof != null) {
            List<Node> members = proof.declaredPlaceholderSet(); out.writeInt(members.size());
            for (Node member : members) nodes.writeNode(out, member, depth + 1);
        }
    }

    private ComponentSnapshot readComponent(DataInputStream in, int depth) throws IOException {
        String identity = requiredText(in), state = requiredText(in); long generation = in.readLong();
        ComponentKind kind = ComponentKind.valueOf(requiredText(in));
        List<DocumentId> documents = new ArrayList<DocumentId>();
        for (int remaining = count(in, false); remaining > 0; remaining--) documents.add(documentId(in));
        List<String> blueIds = new ArrayList<String>();
        for (int remaining = count(in, false); remaining > 0; remaining--) blueIds.add(requiredText(in));
        String master = readText(in), proofIdentity = readText(in);
        CyclicSetProof proof = null;
        if (readBoolean(in)) {
            List<Node> members = new ArrayList<Node>();
            for (int remaining = count(in, false); remaining > 0; remaining--) members.add(nodes.readNode(in, depth + 1));
            proof = CyclicSetProof.fromDeclaredPlaceholderSet(members);
        }
        return new ComponentSnapshot(identity, state, generation, kind, documents, blueIds, master, proof, proofIdentity);
    }

    private static DocumentId documentId(DataInputStream in) throws IOException {
        return new DocumentId(requiredText(in));
    }

    static void verifySnapshot(AffectedClosureSnapshot snapshot) {
        verifySnapshot(snapshot, null);
    }

    void verifySelected(AffectedClosureSnapshot snapshot) { verifySnapshot(snapshot, beforeFullVerification); }

    private static void verifySnapshot(AffectedClosureSnapshot snapshot, Runnable beforeFullVerification) {
        ClosureEvidenceVerifier.verifySnapshot(snapshot, beforeFullVerification);
        if (!snapshot.occurrenceBindingSetIdentity().equals(
                ClosureIdentityService.INSTANCE.occurrenceBindingSetIdentity(snapshot.occurrences()))
                || !snapshot.closureIdentity().equals(ClosureIdentityService.INSTANCE.affectedClosureIdentity(snapshot))) {
            throw invalid("Stored snapshot identity differs from its complete exact evidence");
        }
    }
}
