package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only context used while binding a handler to a channel.
 *
 * <p>The context exposes exact same-scope headers and type identities, never
 * mutable document state. Catalog queries are captured by the supplied
 * runtime-work session so hosted registration work participates in the same
 * deterministic gas and dependency boundary.</p>
 */
public final class HandlerRegistrationContext {

    private final String scopePath;
    private final String handlerKey;
    private final Map<String, FrozenNode> contracts;
    private final Map<String, String> contractTypeBlueIds;
    private final NodeToObjectConverter converter;
    private final CanonicalTypeIdentityLookup typeIdentities;
    private final ContractHeaderMappingEvidence mappingEvidence;
    private final RuntimeWorkSession runtimeWorkSession;

    HandlerRegistrationContext(String scopePath,
                               String handlerKey,
                               Map<String, FrozenNode> contracts,
                               Map<String, String> contractTypeBlueIds,
                               NodeToObjectConverter converter,
                               RuntimeWorkSession runtimeWorkSession,
                               CanonicalTypeIdentityLookup typeIdentities,
                               ContractHeaderMappingEvidence mappingEvidence) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.handlerKey = Objects.requireNonNull(handlerKey, "handlerKey");
        this.contracts = Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
        this.contractTypeBlueIds = Collections.unmodifiableMap(new LinkedHashMap<>(contractTypeBlueIds));
        this.converter = Objects.requireNonNull(converter, "converter");
        this.runtimeWorkSession = runtimeWorkSession;
        this.typeIdentities = Objects.requireNonNull(
                typeIdentities, "typeIdentities");
        this.mappingEvidence = Objects.requireNonNull(
                mappingEvidence, "mappingEvidence");
    }

    /**
     * Returns the absolute scope containing the Handler.
     *
     * @return normalized scope path
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the Handler's raw contract key.
     *
     * @return Handler key
     */
    public String handlerKey() {
        return handlerKey;
    }

    /**
     * Returns every exact contract key in the captured same-scope header map.
     *
     * @return immutable key set
     */
    public Set<String> contractKeys() {
        return contracts.keySet();
    }

    /**
     * Tests whether the captured header map contains a contract key.
     *
     * @param key raw same-scope contract key
     * @return {@code true} when the key is present
     */
    public boolean hasContract(String key) {
        return contracts.containsKey(key);
    }

    /**
     * Returns a contract's captured effective type identity.
     *
     * @param key raw same-scope contract key
     * @return effective type BlueId, or {@code null}
     */
    public String contractTypeBlueId(String key) {
        return contractTypeBlueIds.get(key);
    }

    /**
     * Returns a contract's immutable captured header.
     *
     * @param key raw same-scope contract key
     * @return frozen contract header, or {@code null}
     */
    public FrozenNode frozenContractNode(String key) {
        return contracts.get(key);
    }

    /**
     * Materializes a detached mutable copy of a captured contract header.
     *
     * @param key raw same-scope contract key
     * @return detached contract node, or {@code null}
     */
    public Node contractNode(String key) {
        FrozenNode node = contracts.get(key);
        return node != null ? node.toNode() : null;
    }

    /**
     * Converts a captured contract header to an exact Java model.
     *
     * @param <T> requested contract model
     * @param key raw same-scope contract key
     * @param type exact Java model class
     * @return converted contract, or {@code null} when the key is absent
     */
    public <T extends Contract> T contractAs(String key, Class<T> type) {
        FrozenNode node = contracts.get(key);
        if (node == null) {
            return null;
        }
        return mappingEvidence.convertContract(
                key,
                node.toNode(),
                Collections.<String>emptyList(),
                type,
                false,
                converter,
                typeIdentities);
    }

    /**
     * Returns the live hosted-runtime work session for registration.
     *
     * @return invocation-owned runtime work session
     * @throws IllegalStateException for legacy out-of-band registration
     */
    public RuntimeWorkSession runtimeWorkSession() {
        if (runtimeWorkSession == null) {
            throw new IllegalStateException(
                    "Runtime work is unavailable during out-of-band handler registration");
        }
        return runtimeWorkSession;
    }
}
