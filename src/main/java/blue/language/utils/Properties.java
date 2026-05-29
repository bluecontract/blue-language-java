package blue.language.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Properties {

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
    public static final List<String> BASIC_TYPES = Arrays.asList(TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE);
    public static final List<String> CORE_TYPES =
            Arrays.asList(TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE, LIST_TYPE, DICTIONARY_TYPE);


    public static final String TEXT_TYPE_BLUE_ID = "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC";
    public static final String DOUBLE_TYPE_BLUE_ID = "9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ";
    public static final String INTEGER_TYPE_BLUE_ID = "E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq";
    public static final String BOOLEAN_TYPE_BLUE_ID = "AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2";
    public static final String LIST_TYPE_BLUE_ID = "8DSFoWG9MqRSUhStqoPLrwVQiYByRh18NWbDEarN8MKF";
    public static final String DICTIONARY_TYPE_BLUE_ID = "Efkz9D1ARMM7rU43w3rDNVqat1naS6qXKCqP4eHin3yG";
    public static final List<String> BASIC_TYPE_BLUE_IDS = Arrays.asList(TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID);
    public static final List<String> CORE_TYPE_BLUE_IDS =
            Arrays.asList(TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID, LIST_TYPE_BLUE_ID, DICTIONARY_TYPE_BLUE_ID);

    public static final Map<String, String> CORE_TYPE_NAME_TO_BLUE_ID_MAP = IntStream.range(0, CORE_TYPES.size())
            .boxed()
            .collect(Collectors.toMap(CORE_TYPES::get, CORE_TYPE_BLUE_IDS::get));

    public static final Map<String, String> CORE_TYPE_BLUE_ID_TO_NAME_MAP = IntStream.range(0, CORE_TYPES.size())
            .boxed()
            .collect(Collectors.toMap(CORE_TYPE_BLUE_IDS::get, CORE_TYPES::get));

    public static final List<String> BLUE_CONTRACTS_RUNTIME_TYPES = Arrays.asList(
            "Contract",
            "Json Patch Entry",
            "Contract Execution Result",
            "Channel",
            "Handler",
            "Marker",
            "Process Embedded",
            "Processing Initialized Marker",
            "Processing Terminated Marker",
            "Channel Event Checkpoint",
            "Type Generalization Policy",
            "Type Generalization Rule",
            "Document Update Channel",
            "Triggered Event Channel",
            "Lifecycle Event Channel",
            "Embedded Node Channel",
            "Document Update",
            "Document Processing Initiated",
            "Document Processing Terminated",
            "Document Processing Fatal Error"
    );

    public static final List<String> BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_IDS = Arrays.asList(
            "6WrVQoSpKHUUg5HPrwjkVV6pxe4sdkyGnakMs8ayEGeF",
            "61W96XosAp3DrEC7PuqLYtmF2A6ETpqH6qF2DgYwDq4c",
            "AMtAXPmvumgz1GxKUU9uv3ncXiKMENvqq8AaLvD5LXhv",
            "4FAZ94JPExNM4pn2ZhtdHa4CVP7uASmLNVrBy7aCG1p5",
            "7X46P3Q6FJrogqKrBXTALpqzkieyyiQeatnqLvWzAPXE",
            "6zqbYGDGrMv5ReuEsjyzyyjjuqVnqDZxtY7RsPXdBTNy",
            "8FVc8MPz6DcTMgcY3RXU6EBpGa9arWPJ141K2H86yi8Q",
            "6JjyUKoK7uJxA5NY9YhMaKJbXC6c9iHyx1khv4gaAq4Q",
            "GBDBthfshBFr4GQKUU1fmy4GnPL7q2y3as4deUWpuBtu",
            "9GEC24YbFG9hj4banjYh2oEnDpAob1wAPmhjuykJp8T1",
            "Fbenow6tanFHkWzKiDD8fGxminQswQ1FecMRakaCx2WX",
            "7Vnmk8StjwY7e9mBNpACrn8oh3KZ7yQBjnXe5bLDWn4D",
            "Ac9LC5T7pHVa1TtkhMBjBRtxecShzvbe7ugUdXT1Mu2o",
            "5HwxfbwRBCxG8xYpowWkCPC9akqUSKV7So2M4QHEmLsZ",
            "2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ",
            "H6iUJp3GcLypsJDimMSVoxQQdxxuD8j6eqEUWWqCZ6i",
            "7HEaG1SpBdsbVHsrwRTZSZGmpJUWHfFoEzecYWpjo1vm",
            "Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL",
            "4HWncQEQsdpk8zcXxYxgdtoXo5nKHxFPWeJfTscCbmeK",
            "AMZbj5tNGxjPrvaNyw56sfqcLSW2j1XmkncEYUVtgmVC"
    );

    public static final Map<String, String> BLUE_CONTRACTS_RUNTIME_TYPE_NAME_TO_BLUE_ID_MAP =
            IntStream.range(0, BLUE_CONTRACTS_RUNTIME_TYPES.size())
                    .boxed()
                    .collect(Collectors.toMap(BLUE_CONTRACTS_RUNTIME_TYPES::get, BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_IDS::get));

    public static final Map<String, String> BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_ID_TO_NAME_MAP =
            IntStream.range(0, BLUE_CONTRACTS_RUNTIME_TYPES.size())
                    .boxed()
                    .collect(Collectors.toMap(BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_IDS::get, BLUE_CONTRACTS_RUNTIME_TYPES::get));

    public static final Map<String, String> DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP = buildDefaultBlueTypeNameToBlueIdMap();
    public static final Map<String, String> DEFAULT_BLUE_TYPE_BLUE_ID_TO_NAME_MAP = buildDefaultBlueTypeBlueIdToNameMap();

    private static Map<String, String> buildDefaultBlueTypeNameToBlueIdMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.putAll(CORE_TYPE_NAME_TO_BLUE_ID_MAP);
        result.putAll(BLUE_CONTRACTS_RUNTIME_TYPE_NAME_TO_BLUE_ID_MAP);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> buildDefaultBlueTypeBlueIdToNameMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.putAll(CORE_TYPE_BLUE_ID_TO_NAME_MAP);
        result.putAll(BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_ID_TO_NAME_MAP);
        return Collections.unmodifiableMap(result);
    }

}
