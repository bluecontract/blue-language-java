package blue.language.api;

import blue.language.Blue;
import blue.language.BlueCachePolicy;
import blue.language.NodeProvider;
import blue.language.api.internal.LegacyBlueGraph;
import blue.language.api.internal.LegacyBlueMatching;
import blue.language.api.internal.LegacyBluePatching;
import blue.language.api.internal.LegacyBluePreprocessing;
import blue.language.api.internal.LegacyBlueResolution;
import blue.language.api.internal.LegacyBlueSnapshots;
import blue.language.codec.BlueCodec;
import blue.language.codec.StandardBlueCodec;
import blue.language.graph.BlueGraph;
import blue.language.identity.BlueIdentity;
import blue.language.identity.StandardBlueIdentity;
import blue.language.matching.BlueMatching;
import blue.language.patching.BluePatching;
import blue.language.preprocess.BluePreprocessing;
import blue.language.resolve.BlueResolution;
import blue.language.snapshot.BlueSnapshots;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Small immutable composition root for the focused Blue Language services.
 *
 * <p>Configuration is frozen by {@link Builder#build()}. The resulting
 * runtime owns bounded caches and is safe to share subject to the thread-safety
 * contract of the supplied provider. Closing the composition releases all
 * runtime-owned state.</p>
 */
public final class BlueLanguage implements AutoCloseable {

    private static final NodeProvider EMPTY_PROVIDER = blueId -> null;

    private final Blue compatibilityRuntime;
    private final BlueCodec codec;
    private final BluePreprocessing preprocessing;
    private final BlueGraph graph;
    private final BlueResolution resolution;
    private final BlueIdentity identity;
    private final BlueSnapshots snapshots;
    private final BlueMatching matching;
    private final BluePatching patching;

    private BlueLanguage(Builder builder) {
        this.compatibilityRuntime = new Blue(
                builder.nodeProvider,
                null,
                null,
                builder.cachePolicy);
        if (!builder.preprocessingAliases.isEmpty()) {
            compatibilityRuntime.preprocessingAliases(
                    builder.preprocessingAliases);
        }
        this.codec = new StandardBlueCodec();
        this.preprocessing = new LegacyBluePreprocessing(
                compatibilityRuntime, builder.preprocessingAliases);
        this.graph = new LegacyBlueGraph(compatibilityRuntime);
        this.resolution = new LegacyBlueResolution(
                compatibilityRuntime);
        this.identity = new StandardBlueIdentity(
                compatibilityRuntime::canonicalize);
        this.snapshots = new LegacyBlueSnapshots(
                compatibilityRuntime);
        this.matching = new LegacyBlueMatching(compatibilityRuntime);
        this.patching = new LegacyBluePatching(compatibilityRuntime);
    }

    /** Returns a new independently configurable runtime builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the stateless strict JSON/YAML codec. */
    public BlueCodec codec() {
        return codec;
    }

    /** Returns the configured deterministic preprocessing service. */
    public BluePreprocessing preprocessing() {
        return preprocessing;
    }

    /** Returns exact expansion, collapse, and specialization operations. */
    public BlueGraph graph() {
        return graph;
    }

    /** Returns complete and demand-limited resolution operations. */
    public BlueResolution resolution() {
        return resolution;
    }

    /** Returns direct, Source Document, and cyclic-set identity operations. */
    public BlueIdentity identity() {
        return identity;
    }

    /** Returns immutable snapshot and runtime-owned cache operations. */
    public BlueSnapshots snapshots() {
        return snapshots;
    }

    /** Returns mutable and immutable matching operations. */
    public BlueMatching matching() {
        return matching;
    }

    /** Returns immutable canonical patching operations. */
    public BluePatching patching() {
        return patching;
    }

    /** Releases bounded caches and rejects later admitted runtime operations. */
    @Override
    public void close() {
        compatibilityRuntime.close();
    }

    /** Mutable single-threaded configuration scope for one runtime. */
    public static final class Builder {
        private NodeProvider nodeProvider = EMPTY_PROVIDER;
        private BlueCachePolicy cachePolicy =
                BlueCachePolicy.boundedDefaults();
        private Map<String, String> preprocessingAliases =
                Collections.emptyMap();

        private Builder() {
        }

        /** Configures the borrowed provider used by graph operations. */
        public Builder nodeProvider(NodeProvider nodeProvider) {
            this.nodeProvider = Objects.requireNonNull(
                    nodeProvider, "nodeProvider");
            return this;
        }

        /** Configures immutable runtime-owned cache bounds. */
        public Builder cachePolicy(BlueCachePolicy cachePolicy) {
            this.cachePolicy = Objects.requireNonNull(
                    cachePolicy, "cachePolicy");
            return this;
        }

        /** Freezes explicit aliases used only by root {@code blue} values. */
        public Builder preprocessingAliases(
                Map<String, String> preprocessingAliases) {
            this.preprocessingAliases = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            preprocessingAliases,
                            "preprocessingAliases")));
            return this;
        }

        /** Builds an independent runtime with no process-global registration. */
        public BlueLanguage build() {
            return new BlueLanguage(this);
        }
    }
}
