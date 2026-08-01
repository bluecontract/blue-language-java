package blue.language.api.internal;

import blue.language.Blue;
import blue.language.BlueCacheStats;
import blue.language.model.Node;
import blue.language.snapshot.BlueSnapshots;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import static blue.language.utils.Properties.OBJECT_BLUE;

/** Focused snapshot/cache adapter over the compatibility runtime. */
public final class LegacyBlueSnapshots implements BlueSnapshots {

    private final Blue blue;

    public LegacyBlueSnapshots(Blue blue) {
        this.blue = Objects.requireNonNull(blue, OBJECT_BLUE);
    }

    @Override
    public ResolvedSnapshot resolve(Node source) {
        return blue.resolveToSnapshot(source);
    }

    @Override
    public ResolvedSnapshot resolvePreservingPaths(
            Node source, Collection<String> preservedPaths) {
        return blue.resolveToSnapshotPreservingPaths(
                source, preservedPaths);
    }

    @Override
    public ResolvedSnapshot load(Node canonicalIdentityInput) {
        return blue.loadSnapshot(canonicalIdentityInput);
    }

    @Override
    public ResolvedSnapshot load(String blueId) {
        return blue.loadSnapshot(blueId);
    }

    @Override
    public ResolvedSnapshot cache(ResolvedSnapshot snapshot) {
        blue.cacheResolvedSnapshot(snapshot);
        return snapshot;
    }

    @Override
    public Optional<ResolvedSnapshot> cached(String blueId) {
        return blue.cachedResolvedSnapshot(blueId);
    }

    @Override
    public void clear() {
        blue.clearResolvedSnapshotCache();
    }

    @Override
    public BlueCacheStats stats() {
        return blue.cacheStats();
    }
}
