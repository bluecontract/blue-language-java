package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import static blue.language.processor.closure.ClosureValueSupport.GasAttributionPrefix.CHECKPOINT_SETTLEMENT_WRITE;
import static blue.language.processor.closure.ClosureValueSupport.GasAttributionPrefix.DIRECT_ADMISSION;
import static blue.language.processor.closure.ClosureValueSupport.GasAttributionPrefix.WORK;
import static blue.language.processor.closure.ClosureValueSupport.matchesGasAttributionPrefix;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gas-attribution vocabulary is separate from document-pointer ancestry. */
final class ClosureGasAttributionPrefixTest {
    @Test
    void shouldMatchOnlyTheExactClosedGasAttributionPrefixes() {
        // given
        String admission = "direct-admission.12.classification";
        String checkpoint = "checkpoint-settlement.0.write.source-site";
        String work = "work/42";

        // when
        boolean admissionMatches = matchesGasAttributionPrefix(admission, DIRECT_ADMISSION);
        boolean checkpointMatches = matchesGasAttributionPrefix(checkpoint, CHECKPOINT_SETTLEMENT_WRITE);
        boolean workMatches = matchesGasAttributionPrefix(work, WORK);

        // then
        assertTrue(admissionMatches);
        assertTrue(checkpointMatches);
        assertTrue(workMatches);
        assertFalse(matchesGasAttributionPrefix("direct-admissionX.12", DIRECT_ADMISSION));
        assertFalse(matchesGasAttributionPrefix("checkpoint-settlement.0.writeX.site", CHECKPOINT_SETTLEMENT_WRITE));
        assertFalse(matchesGasAttributionPrefix("checkpoint-settlement.1.write.site", CHECKPOINT_SETTLEMENT_WRITE));
        assertFalse(matchesGasAttributionPrefix("worker/42", WORK));
        assertFalse(matchesGasAttributionPrefix("/work/42", WORK));
        assertFalse(matchesGasAttributionPrefix("Work/42", WORK));
        assertFalse(matchesGasAttributionPrefix("", WORK));
    }
}
