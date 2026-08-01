package blue.language.model.wire;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Model-owned Language wire keys, merge controls, type names, and released
 * type identities.
 *
 * <p>The model is the lowest ownership boundary for these protocol values.
 * Higher layers may expose compatibility facades, but must not redefine the
 * spellings or identities.</p>
 */
public class BlueLanguageConstants {

    public static final String OBJECT_NAME = "name";
    public static final String OBJECT_DESCRIPTION = "description";
    public static final String OBJECT_TYPE = "type";
    public static final String OBJECT_ITEM_TYPE = "itemType";
    public static final String OBJECT_KEY_TYPE = "keyType";
    public static final String OBJECT_VALUE_TYPE = "valueType";
    public static final String OBJECT_SCHEMA = "schema";
    public static final String OBJECT_CONTRACTS = "contracts";
    public static final String OBJECT_MERGE_POLICY = "mergePolicy";
    public static final String OBJECT_VALUE = "value";
    public static final String OBJECT_ITEMS = "items";
    public static final String OBJECT_BLUE_ID = "blueId";
    public static final String OBJECT_BLUE = "blue";
    public static final String BLUE_DIRECTIVE_IMPORTS = "imports";
    public static final String BLUE_DIRECTIVE_TRANSFORMATIONS =
            "transformations";
    public static final String LEGACY_OBJECT_PROPERTIES = "properties";
    public static final String LEGACY_OBJECT_CONSTRAINTS = "constraints";
    public static final String BOOLEAN_TEXT_TRUE = "true";
    public static final String BOOLEAN_TEXT_FALSE = "false";

    public static final String LIST_MERGE_POLICY_POSITIONAL = "positional";
    public static final String LIST_MERGE_POLICY_APPEND_ONLY = "append-only";
    public static final String LIST_CONTROL_PREVIOUS = "$previous";
    public static final String LIST_CONTROL_POS = "$pos";
    public static final String LIST_CONTROL_REPLACE = "$replace";
    public static final String LIST_CONTROL_EMPTY = "$empty";

    public static final String TEXT_TYPE = "Text";
    public static final String DOUBLE_TYPE = "Double";
    public static final String INTEGER_TYPE = "Integer";
    public static final String BOOLEAN_TYPE = "Boolean";
    public static final String LIST_TYPE = "List";
    public static final String DICTIONARY_TYPE = "Dictionary";
    public static final List<String> BASIC_TYPES = Arrays.asList(
            TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE);
    public static final List<String> CORE_TYPES = Arrays.asList(
            TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE,
            LIST_TYPE, DICTIONARY_TYPE);

    public static final String TEXT_TYPE_BLUE_ID =
            "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC";
    public static final String DOUBLE_TYPE_BLUE_ID =
            "9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ";
    public static final String INTEGER_TYPE_BLUE_ID =
            "E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq";
    public static final String BOOLEAN_TYPE_BLUE_ID =
            "AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2";
    public static final String LIST_TYPE_BLUE_ID =
            "8DSFoWG9MqRSUhStqoPLrwVQiYByRh18NWbDEarN8MKF";
    public static final String DICTIONARY_TYPE_BLUE_ID =
            "Efkz9D1ARMM7rU43w3rDNVqat1naS6qXKCqP4eHin3yG";
    public static final List<String> BASIC_TYPE_BLUE_IDS = Arrays.asList(
            TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID,
            INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID);
    public static final List<String> CORE_TYPE_BLUE_IDS = Arrays.asList(
            TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID,
            INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID,
            LIST_TYPE_BLUE_ID, DICTIONARY_TYPE_BLUE_ID);

    public static final Map<String, String> CORE_TYPE_NAME_TO_BLUE_ID_MAP =
            IntStream.range(0, CORE_TYPES.size())
                    .boxed()
                    .collect(Collectors.toMap(
                            CORE_TYPES::get, CORE_TYPE_BLUE_IDS::get));
    public static final Map<String, String> CORE_TYPE_BLUE_ID_TO_NAME_MAP =
            IntStream.range(0, CORE_TYPES.size())
                    .boxed()
                    .collect(Collectors.toMap(
                            CORE_TYPE_BLUE_IDS::get, CORE_TYPES::get));

    /** Allows a compatibility facade to inherit the canonical constants. */
    protected BlueLanguageConstants() {
    }
}
