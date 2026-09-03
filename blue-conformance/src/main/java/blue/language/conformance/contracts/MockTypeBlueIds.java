package blue.language.conformance.contracts;

import blue.language.processor.registry.RuntimeBlueIds;

/**
 * BlueIds for the fixed conformance-only channel and handler types.
 */
final class MockTypeBlueIds {

    /** BlueId of {@link MockExternalChannel}. */
    public static final String MOCK_EXTERNAL_CHANNEL =
            RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL;
    /** BlueId of {@link MockHandler}. */
    public static final String MOCK_HANDLER =
            RuntimeBlueIds.SCRIPTED_HANDLER;
    /** BlueId of the conformance-only {@link MockOperation}. */
    public static final String MOCK_OPERATION =
            "3awNZ6spv8gmjB9m5Sihj73diGVjVN33e14Vg67zEGFp";

    private MockTypeBlueIds() {
    }
}
