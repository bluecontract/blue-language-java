package blue.language.processor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessorPointerConstantsTest {

    @Test
    void shouldVerifyReservedPointersMatchExpectedPaths() {
        // given
        String expectedContracts = "/contracts";
        String expectedInitialized = "/contracts/initialized";
        String expectedTerminated = "/contracts/terminated";
        String expectedEmbedded = "/contracts/embedded";
        String expectedCheckpoint = "/contracts/checkpoint";

        // when
        String contracts = ProcessorPointerConstants.RELATIVE_CONTRACTS;
        String initialized =
                ProcessorPointerConstants.RELATIVE_INITIALIZED;
        String terminated =
                ProcessorPointerConstants.RELATIVE_TERMINATED;
        String embedded = ProcessorPointerConstants.RELATIVE_EMBEDDED;
        String checkpoint =
                ProcessorPointerConstants.RELATIVE_CHECKPOINT;

        // then
        assertEquals(expectedContracts, contracts);
        assertEquals(expectedInitialized, initialized);
        assertEquals(expectedTerminated, terminated);
        assertEquals(expectedEmbedded, embedded);
        assertEquals(expectedCheckpoint, checkpoint);
    }

    @Test
    void shouldVerifyContractsEntryAppendsKeyWithoutDuplicatingSeparators() {
        // given
        String simpleKey = "custom";
        String escapedKey = "a/b~c";

        // when
        String simplePointer =
                ProcessorPointerConstants.relativeContractsEntry(
                        simpleKey);
        String escapedPointer =
                ProcessorPointerConstants.relativeContractsEntry(
                        escapedKey);

        // then
        assertEquals("/contracts/custom", simplePointer);
        assertEquals("/contracts/a~1b~0c", escapedPointer);
    }

    @Test
    void shouldVerifyCheckpointEntryPointerIncludesChannelKey() {
        // given
        String checkpoint = "checkpoint";
        String channel = "channelA";

        // when
        String pointer =
                ProcessorPointerConstants.relativeCheckpointEntry(
                        checkpoint, channel);
        String escapedPointer =
                ProcessorPointerConstants.relativeCheckpointEntry(
                        "check/point", "channel~A");

        // then
        assertEquals(
                "/contracts/checkpoint/entries/channelA",
                pointer);
        assertEquals(
                "/contracts/check~1point/entries/channel~0A",
                escapedPointer);
    }
}
