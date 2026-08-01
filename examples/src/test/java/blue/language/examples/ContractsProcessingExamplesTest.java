package blue.language.examples;

import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractsProcessingExamplesTest {

    @Test
    void shouldRunCustomExternalChannelAndHandlerExample() {
        // given
        String expectedSource = ContractsExampleSupport.SOURCE_CHANNEL_KEY;
        String expectedTarget = ContractsExampleSupport.TARGET_CHANNEL_KEY;

        // when
        CustomExternalChannelExample.Result result =
                CustomExternalChannelExample.run();

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.getStatus());
        assertEquals(BigInteger.valueOf(7L), result.getCounter());
        assertTrue(result.getTotalGas() > 0L);
        assertEquals(expectedSource, result.getSourceChannelKey());
        assertEquals(expectedTarget, result.getHandlerChannelKey());
        assertNotEquals(result.getSourceChannelKey(),
                result.getHandlerChannelKey());
    }

    @Test
    void shouldReturnOnlyRootScopeApplicationEvents() {
        // given / when
        RootOnlyEventsExample.Result result =
                RootOnlyEventsExample.run();

        // then
        assertEquals(0, result.getChildEventCount());
        assertEquals(1, result.getRootEventCount());
        assertEquals(ContractsExampleSupport.ROOT_SCOPE,
                result.getPublicEventOrigin());
    }

    @Test
    void shouldMergeRuntimeChildGasIntoProcessTotal() {
        // given / when
        RuntimeChildGasLedgerExample.Result result =
                RuntimeChildGasLedgerExample.run();

        // then
        assertEquals(21L, result.getChildGas());
        assertTrue(result.getProcessGas() >= result.getChildGas());
    }

    @Test
    void shouldProcessPureReferenceInputsThroughExactFragments() {
        // given / when
        PureReferenceFragmentsExample.Result result =
                PureReferenceFragmentsExample.run();

        // then
        assertEquals(BigInteger.valueOf(5L), result.getCounter());
        assertFalse(result.getRootBlueId().isEmpty());
        assertFalse(result.getEventBlueId().isEmpty());
        assertTrue(result.getRequestedBlueIds().size() >= 3);
    }
}
