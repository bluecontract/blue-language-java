package blue.language.processor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessorPointerConstantsTest {

    @Test
    void reservedPointersMatchExpectedPaths() {
        assertEquals("/contracts", ProcessorPointerConstants.RELATIVE_CONTRACTS);
        assertEquals("/contracts/initialized", ProcessorPointerConstants.RELATIVE_INITIALIZED);
        assertEquals("/contracts/terminated", ProcessorPointerConstants.RELATIVE_TERMINATED);
        assertEquals("/contracts/embedded", ProcessorPointerConstants.RELATIVE_EMBEDDED);
        assertEquals("/contracts/checkpoint", ProcessorPointerConstants.RELATIVE_CHECKPOINT);
    }

    @Test
    void contractsEntryAppendsKeyWithoutDuplicatingSeparators() {
        assertEquals("/contracts/custom", ProcessorPointerConstants.relativeContractsEntry("custom"));
        assertEquals("/contracts/a~1b~0c", ProcessorPointerConstants.relativeContractsEntry("a/b~c"));
    }

    @Test
    void checkpointEntryPointerIncludesChannelKey() {
        String pointer = ProcessorPointerConstants.relativeCheckpointLastEvent("checkpoint", "channelA");
        assertEquals("/contracts/checkpoint/entries/channelA", pointer);
        assertEquals("/contracts/check~1point/entries/channel~0A",
                ProcessorPointerConstants.relativeCheckpointLastEvent("check/point", "channel~A"));
    }
}
