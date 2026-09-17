package blue.language.processor.closure;

import java.util.IdentityHashMap;

/**
 * One execution-envelope decode's owned, fully verified nested bytes. This is
 * only an outer canonical-encoding shortcut, never decoded-object interning.
 */
final class ExecutionStorageCall implements AutoCloseable {
    private final int maximumBytes;
    private final int maximumEntries;
    private final IdentityHashMap<Object, byte[]> decodedBytes = new IdentityHashMap<Object, byte[]>();
    private int retainedBytes;
    private int peakBytes;
    private int peakEntries;
    private int snapshotDecodes;
    private int resultDecodes;
    private int snapshotEncodes;
    private int resultEncodes;
    private int snapshotEncodeReuses;
    private int resultEncodeReuses;
    private boolean closed;

    ExecutionStorageCall(int maximumBytes, int maximumEntries) {
        if (maximumBytes < 0 || maximumEntries < 0 || (maximumBytes == 0) != (maximumEntries == 0))
            throw new IllegalArgumentException("Execution reuse bounds must both be positive or both zero");
        this.maximumBytes = maximumBytes;
        this.maximumEntries = maximumEntries;
    }

    AffectedClosureSnapshot decodeSnapshot(byte[] ownedBytes, AffectedClosureSnapshotStorageCodec codec) {
        requireOpen();
        snapshotDecodes++;
        AffectedClosureSnapshot result = codec.decode(ownedBytes);
        retain(result, ownedBytes);
        return result;
    }

    ClosureProcessResult decodeResult(byte[] ownedBytes, ClosureProcessResultStorageCodec codec) {
        requireOpen();
        resultDecodes++;
        ClosureProcessResult result = codec.decode(ownedBytes);
        retain(result, ownedBytes);
        return result;
    }

    byte[] encodeSnapshot(AffectedClosureSnapshot snapshot, AffectedClosureSnapshotStorageCodec codec) {
        requireOpen();
        byte[] bytes = decodedBytes.get(snapshot);
        if (bytes != null) {
            snapshotEncodeReuses++;
            return bytes;
        }
        snapshotEncodes++;
        return codec.encode(snapshot);
    }

    byte[] encodeResult(ClosureProcessResult result, ClosureProcessResultStorageCodec codec) {
        requireOpen();
        byte[] bytes = decodedBytes.get(result);
        if (bytes != null) {
            resultEncodeReuses++;
            return bytes;
        }
        resultEncodes++;
        return codec.encode(result);
    }

    private void retain(Object decoded, byte[] ownedBytes) {
        // Inputs are freshly read private nested arrays, not the caller's outer
        // array. Only a complete successful nested decode reaches this method.
        // The enclosing reader never lends these arrays to outside code.
        if (decodedBytes.containsKey(decoded) || decodedBytes.size() >= maximumEntries
                || ownedBytes.length > maximumBytes - retainedBytes) return;
        decodedBytes.put(decoded, ownedBytes);
        retainedBytes += ownedBytes.length;
        peakBytes = Math.max(peakBytes, retainedBytes);
        peakEntries = Math.max(peakEntries, decodedBytes.size());
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Execution storage call is closed");
    }

    int snapshotDecodes() { return snapshotDecodes; }
    int resultDecodes() { return resultDecodes; }
    int snapshotEncodes() { return snapshotEncodes; }
    int resultEncodes() { return resultEncodes; }
    int snapshotEncodeReuses() { return snapshotEncodeReuses; }
    int resultEncodeReuses() { return resultEncodeReuses; }
    int retainedBytes() { return retainedBytes; }
    int retainedEntries() { return decodedBytes.size(); }
    int peakBytes() { return peakBytes; }
    int peakEntries() { return peakEntries; }

    @Override public void close() {
        closed = true;
        decodedBytes.clear();
        retainedBytes = 0;
    }
}
