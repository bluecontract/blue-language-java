package blue.lang.utils;

import java.util.Arrays;
import java.util.List;

public class Properties {

    public static final String OBJECT_NAME = "name";
    public static final String OBJECT_DESCRIPTION = "description";
    public static final String OBJECT_TYPE = "type";
    public static final String OBJECT_REQUIRED = "required";
    public static final String OBJECT_CONSTRAINTS = "constraints";
    public static final String OBJECT_CHANGE_POLICY = "change policy";
    public static final String OBJECT_VALUE = "value";
    public static final String OBJECT_ITEMS = "items";
    public static final String OBJECT_REF = "ref";
    public static final String OBJECT_BLUE_ID = "blueId";

    public static final String TRANSLATION_TRANSLATION = "translation";
    public static final String TRANSLATION_TRANSLATION_SOURCE = "translation source";
    public static final String TRANSLATION_TRANSLATION_TARGET_LANGUAGE = "translation target language";

    public static final String CONSTRAINT_MIN_LENGTH = "min length";
    public static final String CONSTRAINT_MAX_LENGTH = "max length";
    public static final String CONSTRAINT_PATTERN = "pattern";
    public static final String CONSTRAINT_MINIMUM = "minimum";
    public static final String CONSTRAINT_MAXIMUM = "maximum";
    public static final String CONSTRAINT_EXCLUSIVE_MINIMUM = "exclusive minimum";
    public static final String CONSTRAINT_EXCLUSIVE_MAXIMUM = "exclusive maximum";
    public static final String CONSTRAINT_MULTIPLE_OF = "multiple of";
    public static final String CONSTRAINT_MIN_ITEMS = "min items";
    public static final String CONSTRAINT_MAX_ITEMS = "max items";
    public static final String CONSTRAINT_UNIQUE_ITEMS = "unique items";
    public static final String CONSTRAINT_OPTIONS = "options";
    public static final String CONSTRAINT_CONST = "const";


    public static final String TYPE_NAME = "name";
    public static final String TYPE_SHA256 = "sha256";
    public static final String TYPE_LIST = "list";

    public static final String TEXT_TYPE = "Text";
    public static final String NUMBER_TYPE = "Number";
    public static final String INTEGER_TYPE = "Integer";
    public static final String BOOLEAN_TYPE = "Boolean";
    public static final List<String> BASIC_TYPES = Arrays.asList(TEXT_TYPE, NUMBER_TYPE, INTEGER_TYPE, BOOLEAN_TYPE);

}