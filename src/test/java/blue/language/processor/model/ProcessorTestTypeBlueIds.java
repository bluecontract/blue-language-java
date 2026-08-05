package blue.language.processor.model;

/**
 * Exact content identities of processor-only Java test fixture types.
 *
 * <p>These values are deliberately separate from the published Contracts 1.0
 * identities in {@code RuntimeBlueIds}. Each constant is the BlueId of the
 * simple canonical fixture node whose {@code name} is the corresponding Java
 * class name. {@code ProcessorTestSupport} recalculates and verifies that
 * identity before exposing any fixture through its provider.</p>
 */
public final class ProcessorTestTypeBlueIds {

    /** BlueId of the {@link ApplyBatchPatch} fixture type. */
    public static final String APPLY_BATCH_PATCH =
            "AjWAjR4NcDYJHMhkAkX9DZKqGbHs8vkCRpjXiHRkLPMw";
    /** BlueId of the {@link AssertDocumentUpdate} fixture type. */
    public static final String ASSERT_DOCUMENT_UPDATE =
            "2QCfZuct9TQRCmgE4q6PneDoZFcshqMLYpsNGpxvfwMd";
    /** BlueId of the {@link CutOffProbe} fixture type. */
    public static final String CUT_OFF_PROBE =
            "A8kbVbinjJAPFnbaQgBRCDU6h64xydTHe69kPakvgjbU";
    /** BlueId of the {@link EmitEvents} fixture type. */
    public static final String EMIT_EVENTS =
            "8L41csGU9GJkoza1159y2pYbJ6yGAi4huvgmu44Ah2d5";
    /** BlueId of the {@link IncrementProperty} fixture type. */
    public static final String INCREMENT_PROPERTY =
            "GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv";
    /** BlueId of the {@link MutateEmbeddedPaths} fixture type. */
    public static final String MUTATE_EMBEDDED_PATHS =
            "AYLVESeD9WrEegNra57vKC2RT65VCBqTz5n9f5MieEkA";
    /** BlueId of the {@link MutateEvent} fixture type. */
    public static final String MUTATE_EVENT =
            "EgL9wruNhEJTS5RspenxoyRngKEbXzMwDM4ZZ8gCHsiv";
    /** BlueId of the {@link ProcessingFailureMarker} fixture type. */
    public static final String PROCESSING_FAILURE_MARKER =
            "33kfH8pfk7F1P5zMsuK1Jm3GcSdmTXoFHKjP16DesEco";
    /** BlueId of the {@link RecordDocumentUpdate} fixture type. */
    public static final String RECORD_DOCUMENT_UPDATE =
            "qLb75fi7BHJf8HvxXNTJP8Zo2fCsA3t6Lz5R269qUiC";
    /** BlueId of the {@link RemoveIfPresent} fixture type. */
    public static final String REMOVE_IF_PRESENT =
            "72r7LSWk5VP9Wh1e5KJX2x8Mrr7Yk8d8Zey9QTbDaHBe";
    /** BlueId of the {@link RemoveProperty} fixture type. */
    public static final String REMOVE_PROPERTY =
            "2REa15BDY5EWq4tJsbUaBwhhTG2xSdk2ZyFL1aCpqTVF";
    /** BlueId of the {@link SetProperty} fixture type. */
    public static final String SET_PROPERTY =
            "8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts";
    /** BlueId of the {@link SetPropertyOnEvent} fixture type. */
    public static final String SET_PROPERTY_ON_EVENT =
            "H1qKGon7JWgUU9P8oUiHjxoR5hWbkAzVWWNukXf4cHz";
    /** BlueId of the {@link TerminateScope} fixture type. */
    public static final String TERMINATE_SCOPE =
            "AZNvNsADqpp7ZwAgpQyaQSz4cq3o3RMHZtB3sgDfudD4";
    /** BlueId of the {@link TestEvent} fixture type. */
    public static final String TEST_EVENT =
            "Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf";
    /** BlueId of the {@link TestEventChannel} fixture type. */
    public static final String TEST_EVENT_CHANNEL =
            "BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L";

    /**
     * Legacy test-only BlueId meta-type identity used by unsupported-value
     * fixtures. It is not a Contracts 1.0 runtime-registry entry.
     */
    public static final String LEGACY_BLUE_ID_TYPE =
            "APr87o8Wq358V8onThLEiW44hEn43wFGf9sKbw5TmmYz";

    private ProcessorTestTypeBlueIds() {
    }
}
