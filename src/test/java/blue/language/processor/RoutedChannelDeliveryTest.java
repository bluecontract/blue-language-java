package blue.language.processor;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression boundary for the pre-1.0 target-occurrence architecture.
 */
final class RoutedChannelDeliveryTest {

    @Test
    void compatibilityCarrierCannotCreateAnExecutableOccurrence() {
        ChannelDelivery routed = ChannelDelivery.of(
                new Node().properties("payload", new Node().value("x")),
                "caller-event",
                "caller-checkpoint",
                Boolean.TRUE,
                "caller-target",
                "caller-deduplication-key");

        assertThrows(UnsupportedOperationException.class,
                () -> ChannelEvaluation.matchDeliveries(
                        Collections.singletonList(routed)));
    }

    @Test
    void contracts10EvaluationStillHasOnlyMatchAndNoMatch() {
        ChannelEvaluation matched =
                ChannelEvaluation.match(new Node().value("payload"));

        assertTrue(matched.matches());
        assertTrue(matched.deliveries().isEmpty());
        assertFalse(ChannelEvaluation.noMatch().matches());
    }
}
