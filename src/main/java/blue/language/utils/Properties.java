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
            "Channel",
            "Channel Event Checkpoint",
            "Channel Checkpoint Entry",
            "Contract",
            "Contract Execution Result",
            "Document Processing Initiated",
            "Document Processing Terminated",
            "Document Update",
            "Document Update Channel",
            "Embedded Event Delivery",
            "Embedded Node Channel",
            "External Channel",
            "Contracts Fixture Event",
            "Handler",
            "Json Patch Entry",
            "Lifecycle Event Channel",
            "Marker",
            "Process Embedded",
            "Processing Initialized Marker",
            "Processing Terminated Marker",
            "Runtime Counter Entry",
            "Runtime Ledger",
            "Scripted External Channel",
            "Scripted Handler",
            "Triggered Event Channel",
            "Type Generalization Policy",
            "Type Generalization Rule"
    );

    public static final List<String> BLUE_CONTRACTS_RUNTIME_TYPE_BLUE_IDS = Arrays.asList(
            "CaFMD5Tpz4LbGjJsftT3465hKBWa7Ti6dutYHnCSRQyR",
            "9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR",
            "2uJq8ZJGyUpMiZckxopH2koa7ZFRavVacpu2eGdK2UwY",
            "4ugZ87HaumAJezmgvi2QoqfEdqfwpviQavmak8C8ewF4",
            "6i9NrtN7uqtSYx136MwLyZSiLjJ98aCUCJNHuQvZah6n",
            "D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt",
            "xaVhnN73YeTiJ1vaLGwndpYQE2RsbckfvzihLQsp2Yi",
            "5qmRyRFrX38eVmgtRxUb79R27sG8VJRJcgsafyANxKgG",
            "4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An",
            "58trfDqLwD1F8JiPg86korUKEjgH1NXxgHSMjeLFRSFC",
            "7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN",
            "4wXKQivSASbs6PLnR562Q2XcT52x1bBViGk7cxhQ3swq",
            "5KUZWsqRuW7SyRj1oCK7hRTmJKVCHTiVJboxy4nas8KX",
            "2Ag2NfcWpCfPqBAR7bFAEL9L3roX3UWGUkDq7nN3D4gV",
            "6ibiR9xVJNErraawKrsDzrGS3H5HyUUNwdZbDTDbU2U6",
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo",
            "8nWeksYEXxp5TBnRcYF5u3VsFHvMfxo4zjAFT6MLW8ZD",
            "D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr",
            "5qrHeD39ytiuWtKXStznJHTjDfgAtiPAr3jwHibvQKvR",
            "4c1aabU6a3idKpWPzTRS4upLjCb6eZh3F1PDXkNh7i6v",
            "2fQHvWpJRfkPW9rqcqZKdcEKx4586LDkYR2bWTPRDZEo",
            "EEcehN6F5zKoZbLFvoAqa8hiWKzPY2VFbd3j5qGDELS2",
            "EvkAYvdyHqzvbmUPWGZjztuA1fu3xKVSqq8AqdSasYv7",
            "DT9DtvU5MQbR1NWN46h6JzJFBwyhEWa4iQQHEw6S5QVZ",
            "DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf",
            "8VeXb3GgP88WtosVLu2mamHmbvY8f5cxA9z6yAETbbFz",
            "5BwjjfvodMVCfD2cKChbUMmjEBd83vv5kbEQwAFHcSnv"
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
