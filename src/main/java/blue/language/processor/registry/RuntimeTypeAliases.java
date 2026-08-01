package blue.language.processor.registry;

import blue.language.utils.Properties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable human-readable aliases for the verified Contracts runtime types.
 *
 * <p>The names come from the canonical registry nodes and the BlueIds come
 * from the same eagerly verified registry entries. Keeping this catalog at
 * the Contracts boundary prevents the Language core from depending on
 * Contracts identities while retaining the aggregate runtime's convenient
 * aliases.</p>
 */
public final class RuntimeTypeAliases {

    /** Runtime type name to its published BlueId, in registry-key order. */
    public static final Map<String, String> NAME_TO_BLUE_ID =
            buildNameToBlueId();

    /** Published runtime BlueId to its canonical type name. */
    public static final Map<String, String> BLUE_ID_TO_NAME =
            indexNamesByBlueId(NAME_TO_BLUE_ID);

    /** Core and runtime aliases exposed by the aggregate compatibility API. */
    public static final Map<String, String> AGGREGATE_NAME_TO_BLUE_ID =
            combine(Properties.CORE_TYPE_NAME_TO_BLUE_ID_MAP,
                    NAME_TO_BLUE_ID);

    /** Core and runtime names indexed by BlueId for the aggregate API. */
    public static final Map<String, String> AGGREGATE_BLUE_ID_TO_NAME =
            indexNamesByBlueId(AGGREGATE_NAME_TO_BLUE_ID);

    private RuntimeTypeAliases() {
    }

    private static Map<String, String> buildNameToBlueId() {
        BlueRuntimeTypeRegistry registry =
                BlueRuntimeTypeRegistry.getDefault();
        Map<String, String> aliases = new LinkedHashMap<>();
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            aliases.put(registry.node(key).getName(),
                    registry.blueId(key));
        }
        return Collections.unmodifiableMap(aliases);
    }

    private static Map<String, String> combine(
            Map<String, String> first,
            Map<String, String> second) {
        Map<String, String> combined = new LinkedHashMap<>();
        combined.putAll(first);
        combined.putAll(second);
        return Collections.unmodifiableMap(combined);
    }

    private static Map<String, String> indexNamesByBlueId(
            Map<String, String> aliases) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            names.put(alias.getValue(), alias.getKey());
        }
        return Collections.unmodifiableMap(names);
    }
}
