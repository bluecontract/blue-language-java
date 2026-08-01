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

    /** Wire key for an authored value's display name. */
    public static final String OBJECT_NAME = "name";
    /** Wire key for an authored value's description. */
    public static final String OBJECT_DESCRIPTION = "description";
    /** Wire key for a value's type definition or reference. */
    public static final String OBJECT_TYPE = "type";
    /** Wire key for the element type of a list. */
    public static final String OBJECT_ITEM_TYPE = "itemType";
    /** Wire key for the key type of a dictionary. */
    public static final String OBJECT_KEY_TYPE = "keyType";
    /** Wire key for the value type of a dictionary. */
    public static final String OBJECT_VALUE_TYPE = "valueType";
    /** Wire key for a value's schema constraints. */
    public static final String OBJECT_SCHEMA = "schema";
    /** Wire key for a value's runtime-neutral Contracts definitions. */
    public static final String OBJECT_CONTRACTS = "contracts";
    /** Wire key selecting a list merge policy. */
    public static final String OBJECT_MERGE_POLICY = "mergePolicy";
    /** Wire key carrying one scalar payload. */
    public static final String OBJECT_VALUE = "value";
    /** Wire key carrying an ordered list payload. */
    public static final String OBJECT_ITEMS = "items";
    /** Wire key carrying a BlueId reference or identity annotation. */
    public static final String OBJECT_BLUE_ID = "blueId";
    /** Wire key carrying an authored preprocessing directive. */
    public static final String OBJECT_BLUE = "blue";
    /** Wire key for preprocessing-directive imports. */
    public static final String BLUE_DIRECTIVE_IMPORTS = "imports";
    /** Wire key for preprocessing-directive transformations. */
    public static final String BLUE_DIRECTIVE_TRANSFORMATIONS =
            "transformations";
    /** Legacy wire key formerly used for object properties. */
    public static final String LEGACY_OBJECT_PROPERTIES = "properties";
    /** Legacy wire key formerly used for schema constraints. */
    public static final String LEGACY_OBJECT_CONSTRAINTS = "constraints";
    /** Canonical textual spelling of the Boolean true value. */
    public static final String BOOLEAN_TEXT_TRUE = "true";
    /** Canonical textual spelling of the Boolean false value. */
    public static final String BOOLEAN_TEXT_FALSE = "false";

    /** Released wire value selecting positional list merging. */
    public static final String LIST_MERGE_POLICY_POSITIONAL = "positional";
    /** Released wire value selecting append-only list merging. */
    public static final String LIST_MERGE_POLICY_APPEND_ONLY = "append-only";
    /** List-control key referencing the preceding list identity. */
    public static final String LIST_CONTROL_PREVIOUS = "$previous";
    /** List-control key selecting an authored overlay position. */
    public static final String LIST_CONTROL_POS = "$pos";
    /** List-control key requesting complete list replacement. */
    public static final String LIST_CONTROL_REPLACE = "$replace";
    /** List-control key representing an explicit empty placeholder. */
    public static final String LIST_CONTROL_EMPTY = "$empty";

    /** Released source-level name of the Text core type. */
    public static final String TEXT_TYPE = "Text";
    /** Released source-level name of the Double core type. */
    public static final String DOUBLE_TYPE = "Double";
    /** Released source-level name of the Integer core type. */
    public static final String INTEGER_TYPE = "Integer";
    /** Released source-level name of the Boolean core type. */
    public static final String BOOLEAN_TYPE = "Boolean";
    /** Released source-level name of the List core type. */
    public static final String LIST_TYPE = "List";
    /** Released source-level name of the Dictionary core type. */
    public static final String DICTIONARY_TYPE = "Dictionary";
    /** Ordered names of the scalar basic types. */
    public static final List<String> BASIC_TYPES = Arrays.asList(
            TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE);
    /** Ordered names of all scalar and container core types. */
    public static final List<String> CORE_TYPES = Arrays.asList(
            TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE,
            LIST_TYPE, DICTIONARY_TYPE);

    /** Released BlueId of the Text core type. */
    public static final String TEXT_TYPE_BLUE_ID =
            "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC";
    /** Released BlueId of the Double core type. */
    public static final String DOUBLE_TYPE_BLUE_ID =
            "9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ";
    /** Released BlueId of the Integer core type. */
    public static final String INTEGER_TYPE_BLUE_ID =
            "E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq";
    /** Released BlueId of the Boolean core type. */
    public static final String BOOLEAN_TYPE_BLUE_ID =
            "AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2";
    /** Released BlueId of the List core type. */
    public static final String LIST_TYPE_BLUE_ID =
            "8DSFoWG9MqRSUhStqoPLrwVQiYByRh18NWbDEarN8MKF";
    /** Released BlueId of the Dictionary core type. */
    public static final String DICTIONARY_TYPE_BLUE_ID =
            "Efkz9D1ARMM7rU43w3rDNVqat1naS6qXKCqP4eHin3yG";
    /** Ordered released BlueIds corresponding to {@link #BASIC_TYPES}. */
    public static final List<String> BASIC_TYPE_BLUE_IDS = Arrays.asList(
            TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID,
            INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID);
    /** Ordered released BlueIds corresponding to {@link #CORE_TYPES}. */
    public static final List<String> CORE_TYPE_BLUE_IDS = Arrays.asList(
            TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID,
            INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID,
            LIST_TYPE_BLUE_ID, DICTIONARY_TYPE_BLUE_ID);

    /** Lookup from each released core type name to its BlueId. */
    public static final Map<String, String> CORE_TYPE_NAME_TO_BLUE_ID_MAP =
            IntStream.range(0, CORE_TYPES.size())
                    .boxed()
                    .collect(Collectors.toMap(
                            CORE_TYPES::get, CORE_TYPE_BLUE_IDS::get));
    /** Lookup from each released core type BlueId to its source-level name. */
    public static final Map<String, String> CORE_TYPE_BLUE_ID_TO_NAME_MAP =
            IntStream.range(0, CORE_TYPES.size())
                    .boxed()
                    .collect(Collectors.toMap(
                            CORE_TYPE_BLUE_IDS::get, CORE_TYPES::get));

    /** Allows a compatibility facade to inherit the canonical constants. */
    protected BlueLanguageConstants() {
    }
}
