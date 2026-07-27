package blue.language.processor.registry;

/**
 * Published Blue Contracts and Processor 1.0 runtime identities.
 */
public final class RuntimeBlueIds {

    public static final String REGISTRY_PACKAGE_IDENTITY =
            "sha256:14d5537efbece502ebf430e09805650dd7ea460415a7aa0a8279c2c11d1d6366";

    public static final String BLUE_ID_TYPE =
            "APr87o8Wq358V8onThLEiW44hEn43wFGf9sKbw5TmmYz";

    public static final String CHANNEL =
            "CaFMD5Tpz4LbGjJsftT3465hKBWa7Ti6dutYHnCSRQyR";
    public static final String CHANNEL_EVENT_CHECKPOINT =
            "9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR";
    public static final String CHECKPOINT_ENTRY =
            "2uJq8ZJGyUpMiZckxopH2koa7ZFRavVacpu2eGdK2UwY";
    public static final String CONTRACT =
            "4ugZ87HaumAJezmgvi2QoqfEdqfwpviQavmak8C8ewF4";
    public static final String CONTRACT_EXECUTION_RESULT =
            "6i9NrtN7uqtSYx136MwLyZSiLjJ98aCUCJNHuQvZah6n";
    public static final String DOCUMENT_PROCESSING_INITIATED =
            "D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt";
    public static final String DOCUMENT_PROCESSING_TERMINATED =
            "xaVhnN73YeTiJ1vaLGwndpYQE2RsbckfvzihLQsp2Yi";
    public static final String DOCUMENT_UPDATE =
            "5qmRyRFrX38eVmgtRxUb79R27sG8VJRJcgsafyANxKgG";
    public static final String DOCUMENT_UPDATE_CHANNEL =
            "4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An";
    public static final String EMBEDDED_EVENT_DELIVERY =
            "58trfDqLwD1F8JiPg86korUKEjgH1NXxgHSMjeLFRSFC";
    public static final String EMBEDDED_NODE_CHANNEL =
            "7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN";
    public static final String EXTERNAL_CHANNEL =
            "4wXKQivSASbs6PLnR562Q2XcT52x1bBViGk7cxhQ3swq";
    public static final String FIXTURE_EVENT =
            "5KUZWsqRuW7SyRj1oCK7hRTmJKVCHTiVJboxy4nas8KX";
    public static final String HANDLER =
            "2Ag2NfcWpCfPqBAR7bFAEL9L3roX3UWGUkDq7nN3D4gV";
    public static final String JSON_PATCH_ENTRY =
            "6ibiR9xVJNErraawKrsDzrGS3H5HyUUNwdZbDTDbU2U6";
    public static final String LIFECYCLE_EVENT_CHANNEL =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    public static final String MARKER =
            "8nWeksYEXxp5TBnRcYF5u3VsFHvMfxo4zjAFT6MLW8ZD";
    public static final String PROCESS_EMBEDDED =
            "D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr";
    public static final String PROCESSING_INITIALIZED_MARKER =
            "5qrHeD39ytiuWtKXStznJHTjDfgAtiPAr3jwHibvQKvR";
    public static final String PROCESSING_TERMINATED_MARKER =
            "4c1aabU6a3idKpWPzTRS4upLjCb6eZh3F1PDXkNh7i6v";
    public static final String RUNTIME_COUNTER_ENTRY =
            "2fQHvWpJRfkPW9rqcqZKdcEKx4586LDkYR2bWTPRDZEo";
    public static final String RUNTIME_LEDGER =
            "EEcehN6F5zKoZbLFvoAqa8hiWKzPY2VFbd3j5qGDELS2";
    public static final String SCRIPTED_EXTERNAL_CHANNEL =
            "EvkAYvdyHqzvbmUPWGZjztuA1fu3xKVSqq8AqdSasYv7";
    public static final String SCRIPTED_HANDLER =
            "DT9DtvU5MQbR1NWN46h6JzJFBwyhEWa4iQQHEw6S5QVZ";
    public static final String TRIGGERED_EVENT_CHANNEL =
            "DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf";
    public static final String TYPE_GENERALIZATION_POLICY =
            "8VeXb3GgP88WtosVLu2mamHmbvY8f5cxA9z6yAETbbFz";
    public static final String TYPE_GENERALIZATION_RULE =
            "5BwjjfvodMVCfD2cKChbUMmjEBd83vv5kbEQwAFHcSnv";

    private RuntimeBlueIds() {
    }

    public static String blueId(RuntimeTypeKey key) {
        switch (key) {
            case CHANNEL:
                return CHANNEL;
            case CHANNEL_EVENT_CHECKPOINT:
                return CHANNEL_EVENT_CHECKPOINT;
            case CHECKPOINT_ENTRY:
                return CHECKPOINT_ENTRY;
            case CONTRACT:
                return CONTRACT;
            case CONTRACT_EXECUTION_RESULT:
                return CONTRACT_EXECUTION_RESULT;
            case DOCUMENT_PROCESSING_INITIATED:
                return DOCUMENT_PROCESSING_INITIATED;
            case DOCUMENT_PROCESSING_TERMINATED:
                return DOCUMENT_PROCESSING_TERMINATED;
            case DOCUMENT_UPDATE:
                return DOCUMENT_UPDATE;
            case DOCUMENT_UPDATE_CHANNEL:
                return DOCUMENT_UPDATE_CHANNEL;
            case EMBEDDED_EVENT_DELIVERY:
                return EMBEDDED_EVENT_DELIVERY;
            case EMBEDDED_NODE_CHANNEL:
                return EMBEDDED_NODE_CHANNEL;
            case EXTERNAL_CHANNEL:
                return EXTERNAL_CHANNEL;
            case FIXTURE_EVENT:
                return FIXTURE_EVENT;
            case HANDLER:
                return HANDLER;
            case JSON_PATCH_ENTRY:
                return JSON_PATCH_ENTRY;
            case LIFECYCLE_EVENT_CHANNEL:
                return LIFECYCLE_EVENT_CHANNEL;
            case MARKER:
                return MARKER;
            case PROCESS_EMBEDDED:
                return PROCESS_EMBEDDED;
            case PROCESSING_INITIALIZED_MARKER:
                return PROCESSING_INITIALIZED_MARKER;
            case PROCESSING_TERMINATED_MARKER:
                return PROCESSING_TERMINATED_MARKER;
            case RUNTIME_COUNTER_ENTRY:
                return RUNTIME_COUNTER_ENTRY;
            case RUNTIME_LEDGER:
                return RUNTIME_LEDGER;
            case SCRIPTED_EXTERNAL_CHANNEL:
                return SCRIPTED_EXTERNAL_CHANNEL;
            case SCRIPTED_HANDLER:
                return SCRIPTED_HANDLER;
            case TRIGGERED_EVENT_CHANNEL:
                return TRIGGERED_EVENT_CHANNEL;
            case TYPE_GENERALIZATION_POLICY:
                return TYPE_GENERALIZATION_POLICY;
            case TYPE_GENERALIZATION_RULE:
                return TYPE_GENERALIZATION_RULE;
            default:
                throw new IllegalArgumentException("Unknown runtime type key: " + key);
        }
    }
}
