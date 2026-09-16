package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Bounded physical reuse confined to one top-level storage codec call. */
final class SnapshotStorageCall implements AutoCloseable {
    private final AffectedClosureSnapshotStorageCodec codec;
    private final int maximumBytes;
    private final int maximumEntries;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final List<AffectedClosureSnapshot> verificationOrder = new ArrayList<AffectedClosureSnapshot>();
    private final IdentityHashMap<AffectedClosureSnapshot, Boolean> verified =
            new IdentityHashMap<AffectedClosureSnapshot, Boolean>();
    private int retainedBytes;
    private int peakBytes;
    private int peakEntries;
    private int verificationAttempts;
    private int resultInputVerificationReuses;
    private int resultInputVerificationAttempts;
    private int encodeHits;
    private int decodeHits;
    private boolean closed;

    SnapshotStorageCall(AffectedClosureSnapshotStorageCodec codec, int maximumBytes, int maximumEntries) {
        this.codec = Objects.requireNonNull(codec, "codec");
        if (maximumBytes < 0 || maximumEntries < 0 || (maximumBytes == 0) != (maximumEntries == 0))
            throw new IllegalArgumentException("Snapshot reuse bounds must both be positive or both zero");
        this.maximumBytes = maximumBytes;
        this.maximumEntries = maximumEntries;
    }

    byte[] encode(AffectedClosureSnapshot snapshot) {
        requireOpen();
        Objects.requireNonNull(snapshot, "snapshot");
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.snapshot == snapshot) {
                touch(index);
                encodeHits++;
                return entry.bytes.clone();
            }
        }
        byte[] bytes = codec.encodeInCall(snapshot, this);
        retain(snapshot, bytes, false);
        return bytes;
    }

    AffectedClosureSnapshot decode(byte[] bytes) {
        return decode(bytes, null);
    }

    // Package-only callback exercises the complete decode-to-memo handoff.
    AffectedClosureSnapshot decode(byte[] bytes, Runnable afterSelection) {
        requireOpen();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            // Only a successful full decode plus canonical comparison grants reuse.
            // Exact bytes, including the checksum, are compared; identities are not keys.
            if (entry.decoded && Arrays.equals(entry.bytes, bytes)) {
                touch(index);
                decodeHits++;
                return entry.snapshot;
            }
        }
        AffectedClosureSnapshotStorageCodec.DecodedSnapshot decoded =
                codec.decodeEnvelopeInCall(bytes, this, afterSelection);
        retain(decoded.snapshot, decoded.bytes, true);
        return decoded.snapshot;
    }

    void verify(AffectedClosureSnapshot snapshot) {
        requireOpen();
        if (verified.containsKey(snapshot)) return;
        verificationAttempts++;
        codec.verifySelected(snapshot);
        if (maximumEntries == 0) return;
        if (verificationOrder.size() == maximumEntries) verified.remove(verificationOrder.remove(0));
        verificationOrder.add(snapshot);
        verified.put(snapshot, Boolean.TRUE);
    }

    /** Only a completed full verification of this exact object grants reuse. */
    boolean hasVerified(AffectedClosureSnapshot snapshot) {
        requireOpen();
        return verified.containsKey(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /** Storage construction keeps the ordinary semantic check on every proof miss. */
    void verifyResultInput(AffectedClosureSnapshot snapshot) {
        if (hasVerified(snapshot)) {
            resultInputVerificationReuses++;
        } else {
            resultInputVerificationAttempts++;
            ClosureEvidenceVerifier.verifySnapshot(snapshot);
        }
    }

    private void retain(AffectedClosureSnapshot snapshot, byte[] bytes, boolean decoded) {
        if (maximumEntries == 0 || bytes.length > maximumBytes) return;
        // Evict before retaining a detached array. Bounds cover canonical payload
        // bytes and entry/reference counts, not decoded graph or total JVM heap size.
        while (entries.size() >= maximumEntries || retainedBytes > maximumBytes - bytes.length) {
            retainedBytes -= entries.remove(0).bytes.length;
        }
        entries.add(new Entry(snapshot, bytes.clone(), decoded));
        retainedBytes += bytes.length;
        peakBytes = Math.max(peakBytes, retainedBytes);
        peakEntries = Math.max(peakEntries, entries.size());
    }

    private void touch(int index) { entries.add(entries.remove(index)); }
    private void requireOpen() { if (closed) throw new IllegalStateException("Snapshot storage call is closed"); }

    int verificationAttempts() { return verificationAttempts; }
    int resultInputVerificationReuses() { return resultInputVerificationReuses; }
    int resultInputVerificationAttempts() { return resultInputVerificationAttempts; }
    int encodeHits() { return encodeHits; }
    int decodeHits() { return decodeHits; }
    int retainedBytes() { return retainedBytes; }
    int retainedEntries() { return entries.size(); }
    int retainedVerifications() { return verified.size(); }
    boolean reuseEnabled() { requireOpen(); return maximumEntries != 0; }
    int peakBytes() { return peakBytes; }
    int peakEntries() { return peakEntries; }

    @Override public void close() {
        closed = true;
        entries.clear();
        verified.clear();
        verificationOrder.clear();
        retainedBytes = 0;
    }

    private static final class Entry {
        private final AffectedClosureSnapshot snapshot;
        private final byte[] bytes;
        private final boolean decoded;
        private Entry(AffectedClosureSnapshot snapshot, byte[] bytes, boolean decoded) {
            this.snapshot = snapshot; this.bytes = bytes; this.decoded = decoded;
        }
    }
}
