package blue.language.utils;

import java.util.Arrays;
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
    public static final String OBJECT_CONSTRAINTS = "constraints";
    public static final String OBJECT_VALUE = "value";
    public static final String OBJECT_ITEMS = "items";
    public static final String OBJECT_BLUE_ID = "blueId";
    public static final String OBJECT_BLUE = "blue";

    public static final String TEXT_TYPE = "Text";
    public static final String DOUBLE_TYPE = "Double";
    public static final String INTEGER_TYPE = "Integer";
    public static final String BOOLEAN_TYPE = "Boolean";
    public static final String LIST_TYPE = "List";
    public static final String DICTIONARY_TYPE = "Dictionary";
    public static final List<String> BASIC_TYPES = Arrays.asList(TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE);
    public static final List<String> CORE_TYPES =
            Arrays.asList(TEXT_TYPE, DOUBLE_TYPE, INTEGER_TYPE, BOOLEAN_TYPE, LIST_TYPE, DICTIONARY_TYPE);


    public static final String TEXT_TYPE_BLUE_ID = "F92yo19rCcbBoBSpUA5LRxpfDejJDAaP1PRxxbWAraVP";
    public static final String DOUBLE_TYPE_BLUE_ID = "68ryJtnmui4j5rCZWUnkZ3DChtmEb7Z9F8atn1mBSM3L";
    public static final String INTEGER_TYPE_BLUE_ID = "DHmxTkFbXePZHCHCYmQr2dSzcNLcryFVjXVHkdQrrZr8";
    public static final String BOOLEAN_TYPE_BLUE_ID = "EL6AjrbJsxTWRTPzY8WR8Y2zAMXRbydQj83PcZwuAHbo";
    public static final String LIST_TYPE_BLUE_ID = "G8wmfjEqugPEEXByMYWJXiEdbLToPRWNQEekNxrxfQWB";
    public static final String DICTIONARY_TYPE_BLUE_ID = "294NBTj2mFRL3RB4kDRUSckwGg7Kzj6T8CTAFeR1kcSA";
    public static final List<String> BASIC_TYPE_BLUE_IDS = Arrays.asList(TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID);
    public static final List<String> CORE_TYPE_BLUE_IDS =
            Arrays.asList(TEXT_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID, LIST_TYPE_BLUE_ID, DICTIONARY_TYPE_BLUE_ID);

    public static final Map<String, String> CORE_TYPE_NAME_TO_BLUE_ID_MAP = IntStream.range(0, CORE_TYPES.size())
            .boxed()
            .collect(Collectors.toMap(CORE_TYPES::get, CORE_TYPE_BLUE_IDS::get));

    public static final Map<String, String> CORE_TYPE_BLUE_ID_TO_NAME_MAP = IntStream.range(0, CORE_TYPES.size())
            .boxed()
            .collect(Collectors.toMap(CORE_TYPE_BLUE_IDS::get, CORE_TYPES::get));

}