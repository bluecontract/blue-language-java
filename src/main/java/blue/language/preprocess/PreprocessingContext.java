package blue.language.preprocess;

import blue.language.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only inputs made available to an explicitly declared preprocessing
 * transformation.
 *
 * <p>The context is created only after the whole directive graph has been
 * verified and frozen. It exposes immutable effective imports and the
 * verified provider boundary; neither cache state nor provider location is
 * transformation meaning.</p>
 */
public final class PreprocessingContext {

    private final Map<String, String> effectiveImports;
    private final NodeProvider verifiedProvider;

    /**
     * Creates an immutable context from an established preprocessing plan.
     *
     * @param effectiveImports complete alias-to-BlueId map
     * @param verifiedProvider provider whose results are identity-verified
     */
    public PreprocessingContext(
            Map<String, String> effectiveImports,
            NodeProvider verifiedProvider) {
        this.effectiveImports = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        effectiveImports, "effectiveImports")));
        this.verifiedProvider = Objects.requireNonNull(
                verifiedProvider, "verifiedProvider");
    }

    /**
     * Returns the immutable built-in, host, and directive import map.
     *
     * @return immutable effective imports in deterministic insertion order
     */
    public Map<String, String> effectiveImports() {
        return effectiveImports;
    }

    /**
     * Fetches one exact identity through the verified provider boundary.
     *
     * @param blueId exact requested BlueId
     * @return typed provider conclusion with defensive content copies
     */
    public NodeProviderResult fetchResultByBlueId(String blueId) {
        return verifiedProvider.fetchResultByBlueId(blueId);
    }
}
