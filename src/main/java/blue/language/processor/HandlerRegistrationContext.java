package blue.language.processor;

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
 */
public final class HandlerRegistrationContext {

    private final String scopePath;
    private final String handlerKey;
    private final Map<String, FrozenNode> contracts;
    private final Map<String, String> contractTypeBlueIds;
    private final NodeToObjectConverter converter;

    HandlerRegistrationContext(String scopePath,
                               String handlerKey,
                               Map<String, FrozenNode> contracts,
                               Map<String, String> contractTypeBlueIds,
                               NodeToObjectConverter converter) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.handlerKey = Objects.requireNonNull(handlerKey, "handlerKey");
        this.contracts = Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
        this.contractTypeBlueIds = Collections.unmodifiableMap(new LinkedHashMap<>(contractTypeBlueIds));
        this.converter = Objects.requireNonNull(converter, "converter");
    }

    public String scopePath() {
        return scopePath;
    }

    public String handlerKey() {
        return handlerKey;
    }

    public Set<String> contractKeys() {
        return contracts.keySet();
    }

    public boolean hasContract(String key) {
        return contracts.containsKey(key);
    }

    public String contractTypeBlueId(String key) {
        return contractTypeBlueIds.get(key);
    }

    public FrozenNode frozenContractNode(String key) {
        return contracts.get(key);
    }

    public Node contractNode(String key) {
        FrozenNode node = contracts.get(key);
        return node != null ? node.toNode() : null;
    }

    public <T extends Contract> T contractAs(String key, Class<T> type) {
        FrozenNode node = contracts.get(key);
        if (node == null) {
            return null;
        }
        return converter.convertWithType(node.toNode(), type, false);
    }
}
