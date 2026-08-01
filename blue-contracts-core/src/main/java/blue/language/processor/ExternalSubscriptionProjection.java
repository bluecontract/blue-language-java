package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable sparse Root projection used to verify retained subscriptions. */
final class ExternalSubscriptionProjection {

    final Node root;
    final Map<String, Set<String>> requestedKeys;
    private final Map<String, Map<String, String>>
            selectorTypesByScope;

    ExternalSubscriptionProjection(
            Node root,
            Map<String, Set<String>> requestedKeys,
            Map<String, Map<String, String>> selectorTypesByScope) {
        this.root = Objects.requireNonNull(root, "root");
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry
                : requestedKeys.entrySet()) {
            copy.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(entry.getValue())));
        }
        this.requestedKeys = Collections.unmodifiableMap(copy);
        Map<String, Map<String, String>> typesCopy =
                new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry
                : selectorTypesByScope.entrySet()) {
            typesCopy.put(
                    entry.getKey(),
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(entry.getValue())));
        }
        this.selectorTypesByScope =
                Collections.unmodifiableMap(typesCopy);
    }

    Map<String, String> selectorTypes(String scopePath) {
        return selectorTypesByScope.get(
                PointerUtils.normalizeScope(scopePath));
    }

    List<String> contractKeys(String scopePath) {
        Map<String, String> types = selectorTypes(scopePath);
        if (types == null || types.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>();
        for (String key : types.keySet()) {
            if (!ProcessorContractConstants.KEY_INITIALIZED.equals(key)
                    && !ProcessorContractConstants.KEY_TERMINATED
                    .equals(key)
                    && !ProcessorContractConstants.KEY_CHECKPOINT
                    .equals(key)) {
                keys.add(key);
            }
        }
        keys.sort(ExternalOrderKey::compareTextCodePoints);
        return Collections.unmodifiableList(keys);
    }
}
