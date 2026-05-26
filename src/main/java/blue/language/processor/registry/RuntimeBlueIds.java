package blue.language.processor.registry;

public final class RuntimeBlueIds {

    public static final String BLUE_ID_TYPE = "APr87o8Wq358V8onThLEiW44hEn43wFGf9sKbw5TmmYz";

    public static final String CONTRACT = "6WrVQoSpKHUUg5HPrwjkVV6pxe4sdkyGnakMs8ayEGeF";
    public static final String JSON_PATCH_ENTRY = "61W96XosAp3DrEC7PuqLYtmF2A6ETpqH6qF2DgYwDq4c";
    public static final String CONTRACT_EXECUTION_RESULT = "AMtAXPmvumgz1GxKUU9uv3ncXiKMENvqq8AaLvD5LXhv";
    public static final String CHANNEL = "4FAZ94JPExNM4pn2ZhtdHa4CVP7uASmLNVrBy7aCG1p5";
    public static final String HANDLER = "7X46P3Q6FJrogqKrBXTALpqzkieyyiQeatnqLvWzAPXE";
    public static final String MARKER = "6zqbYGDGrMv5ReuEsjyzyyjjuqVnqDZxtY7RsPXdBTNy";
    public static final String PROCESS_EMBEDDED = "8FVc8MPz6DcTMgcY3RXU6EBpGa9arWPJ141K2H86yi8Q";
    public static final String PROCESSING_INITIALIZED_MARKER = "6JjyUKoK7uJxA5NY9YhMaKJbXC6c9iHyx1khv4gaAq4Q";
    public static final String PROCESSING_TERMINATED_MARKER = "GBDBthfshBFr4GQKUU1fmy4GnPL7q2y3as4deUWpuBtu";
    public static final String CHANNEL_EVENT_CHECKPOINT = "9GEC24YbFG9hj4banjYh2oEnDpAob1wAPmhjuykJp8T1";
    public static final String TYPE_GENERALIZATION_POLICY = "Fbenow6tanFHkWzKiDD8fGxminQswQ1FecMRakaCx2WX";
    public static final String TYPE_GENERALIZATION_RULE = "7Vnmk8StjwY7e9mBNpACrn8oh3KZ7yQBjnXe5bLDWn4D";
    public static final String DOCUMENT_UPDATE_CHANNEL = "Ac9LC5T7pHVa1TtkhMBjBRtxecShzvbe7ugUdXT1Mu2o";
    public static final String TRIGGERED_EVENT_CHANNEL = "5HwxfbwRBCxG8xYpowWkCPC9akqUSKV7So2M4QHEmLsZ";
    public static final String LIFECYCLE_EVENT_CHANNEL = "2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ";
    public static final String EMBEDDED_NODE_CHANNEL = "H6iUJp3GcLypsJDimMSVoxQQdxxuD8j6eqEUWWqCZ6i";
    public static final String DOCUMENT_UPDATE = "7HEaG1SpBdsbVHsrwRTZSZGmpJUWHfFoEzecYWpjo1vm";
    public static final String DOCUMENT_PROCESSING_INITIATED = "Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL";
    public static final String DOCUMENT_PROCESSING_TERMINATED = "4HWncQEQsdpk8zcXxYxgdtoXo5nKHxFPWeJfTscCbmeK";
    public static final String DOCUMENT_PROCESSING_FATAL_ERROR = "AMZbj5tNGxjPrvaNyw56sfqcLSW2j1XmkncEYUVtgmVC";

    private RuntimeBlueIds() {
    }

    public static String blueId(RuntimeTypeKey key) {
        switch (key) {
            case CONTRACT:
                return CONTRACT;
            case JSON_PATCH_ENTRY:
                return JSON_PATCH_ENTRY;
            case CONTRACT_EXECUTION_RESULT:
                return CONTRACT_EXECUTION_RESULT;
            case CHANNEL:
                return CHANNEL;
            case HANDLER:
                return HANDLER;
            case MARKER:
                return MARKER;
            case PROCESS_EMBEDDED:
                return PROCESS_EMBEDDED;
            case PROCESSING_INITIALIZED_MARKER:
                return PROCESSING_INITIALIZED_MARKER;
            case PROCESSING_TERMINATED_MARKER:
                return PROCESSING_TERMINATED_MARKER;
            case CHANNEL_EVENT_CHECKPOINT:
                return CHANNEL_EVENT_CHECKPOINT;
            case TYPE_GENERALIZATION_POLICY:
                return TYPE_GENERALIZATION_POLICY;
            case TYPE_GENERALIZATION_RULE:
                return TYPE_GENERALIZATION_RULE;
            case DOCUMENT_UPDATE_CHANNEL:
                return DOCUMENT_UPDATE_CHANNEL;
            case TRIGGERED_EVENT_CHANNEL:
                return TRIGGERED_EVENT_CHANNEL;
            case LIFECYCLE_EVENT_CHANNEL:
                return LIFECYCLE_EVENT_CHANNEL;
            case EMBEDDED_NODE_CHANNEL:
                return EMBEDDED_NODE_CHANNEL;
            case DOCUMENT_UPDATE:
                return DOCUMENT_UPDATE;
            case DOCUMENT_PROCESSING_INITIATED:
                return DOCUMENT_PROCESSING_INITIATED;
            case DOCUMENT_PROCESSING_TERMINATED:
                return DOCUMENT_PROCESSING_TERMINATED;
            case DOCUMENT_PROCESSING_FATAL_ERROR:
                return DOCUMENT_PROCESSING_FATAL_ERROR;
            default:
                throw new IllegalArgumentException("Unknown runtime type key: " + key);
        }
    }
}
