package blue.language.conformance.contracts;

import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Physical fixture provider used to make warm/cold and batched/unbatched
 * variants real preparation strategies. Its counters are not exposed through
 * semantic projections or gas traces.
 */
final class ContractsFixturePhysicalProvider implements NodeProvider {

    private final Map<String, Node> backing = new LinkedHashMap<>();
    private final Map<String, Node> cache = new LinkedHashMap<>();
    private final String cacheMode;
    private final String batchingMode;
    private final Set<String> transientlyUnavailable;
    private final Set<String> requestedBlueIds = new LinkedHashSet<>();
    private final int initialCacheEntries;
    private long requests;
    private long backendLoads;
    private int largestBackendLoad;

    ContractsFixturePhysicalProvider(
            Map<String, Node> nodes,
            String cacheMode,
            String batchingMode) {
        this(nodes, cacheMode, batchingMode,
                Collections.<String>emptySet());
    }

    ContractsFixturePhysicalProvider(
            Map<String, Node> nodes,
            String cacheMode,
            String batchingMode,
            Set<String> transientlyUnavailable) {
        if (!"cold".equals(cacheMode)
                && !"warm".equals(cacheMode)) {
            throw new IllegalArgumentException(
                    "Unsupported fixture cache mode: " + cacheMode);
        }
        if (!"unbatched".equals(batchingMode)
                && !"batched".equals(batchingMode)) {
            throw new IllegalArgumentException(
                    "Unsupported fixture batching mode: "
                            + batchingMode);
        }
        this.cacheMode = cacheMode;
        this.batchingMode = batchingMode;
        this.transientlyUnavailable = Collections.unmodifiableSet(
                new LinkedHashSet<String>(Objects.requireNonNull(
                        transientlyUnavailable,
                        "transientlyUnavailable")));
        for (Map.Entry<String, Node> entry : nodes.entrySet()) {
            backing.put(entry.getKey(), entry.getValue().clone());
        }
        if ("warm".equals(cacheMode)) {
            copyAll(backing, cache);
        }
        this.initialCacheEntries = cache.size();
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        requestedBlueIds.add(blueId);
        requests++;
        Node cached = cache.get(blueId);
        if (cached != null) {
            return Collections.singletonList(cached.clone());
        }
        if ("batched".equals(batchingMode)) {
            backendLoads++;
            largestBackendLoad =
                    Math.max(largestBackendLoad, backing.size());
            copyAll(backing, cache);
        } else {
            backendLoads++;
            Node exact = backing.get(blueId);
            if (exact != null) {
                cache.put(blueId, exact.clone());
                largestBackendLoad =
                        Math.max(largestBackendLoad, 1);
            }
        }
        Node loaded = cache.get(blueId);
        return loaded == null
                ? null
                : Collections.singletonList(loaded.clone());
    }

    @Override
    public NodeProviderResult fetchResultByBlueId(String blueId) {
        if (transientlyUnavailable.contains(blueId)) {
            requestedBlueIds.add(blueId);
            requests++;
            backendLoads++;
            largestBackendLoad = Math.max(largestBackendLoad, 1);
            return NodeProviderResult.unavailable(
                    "Fixture exact node is transiently unavailable");
        }
        return NodeProvider.super.fetchResultByBlueId(blueId);
    }

    void verifyPreparation() {
        if ("cold".equals(cacheMode)
                && initialCacheEntries != 0) {
            throw new AssertionError(
                    "Cold provider began with cached content");
        }
        if ("warm".equals(cacheMode)
                && initialCacheEntries != backing.size()) {
            throw new AssertionError(
                    "Warm provider did not preload exact content");
        }
        if ("unbatched".equals(batchingMode)
                && largestBackendLoad > 1) {
            throw new AssertionError(
                    "Unbatched provider performed a bulk load");
        }
        if ("batched".equals(batchingMode)
                && backendLoads > 0
                && largestBackendLoad != backing.size()) {
            throw new AssertionError(
                    "Batched provider did not load one physical batch");
        }
        if (requests > 0
                && "cold".equals(cacheMode)
                && backendLoads == 0) {
            throw new AssertionError(
                    "Cold provider request bypassed physical storage");
        }
    }

    void verifyExpectedLoads(Set<String> expectedBlueIds) {
        Set<String> missing = new LinkedHashSet<String>(
                Objects.requireNonNull(expectedBlueIds,
                        "expectedBlueIds"));
        missing.removeAll(requestedBlueIds);
        if (!missing.isEmpty()) {
            throw new AssertionError(
                    "Fixture provider did not load expected exact nodes: "
                            + missing);
        }
    }

    void verifyExactPhysicalLoads(Set<String> expectedBlueIds) {
        Set<String> expected = new LinkedHashSet<String>(
                Objects.requireNonNull(expectedBlueIds,
                        "expectedBlueIds"));
        if (expected.size() < 2 || backing.size() < 2) {
            throw new AssertionError(
                    "Physical provider matrix requires at least two "
                            + "exact dependencies and backing nodes");
        }
        if (!requestedBlueIds.equals(expected)) {
            throw new AssertionError(
                    "Physical provider requests differ from the exact "
                            + "expected loads: expected=" + expected
                            + ", actual=" + requestedBlueIds);
        }
        if ("warm".equals(cacheMode)) {
            if (backendLoads != 0) {
                throw new AssertionError(
                        "Warm provider performed a backend load");
            }
            return;
        }
        if ("batched".equals(batchingMode)) {
            if (backendLoads != 1
                    || largestBackendLoad != backing.size()) {
                throw new AssertionError(
                        "Cold batched provider did not perform exactly "
                                + "one complete physical batch");
            }
        } else if (backendLoads != expected.size()
                || largestBackendLoad != 1) {
            throw new AssertionError(
                    "Cold unbatched provider did not perform one exact "
                            + "backend load per required dependency");
        }
    }

    private static void copyAll(
            Map<String, Node> source,
            Map<String, Node> target) {
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            target.put(entry.getKey(), entry.getValue().clone());
        }
    }
}
