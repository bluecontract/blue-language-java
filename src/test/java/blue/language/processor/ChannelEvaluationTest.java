package blue.language.processor;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelEvaluationTest {

    @Test
    void shouldDefensivelyCopyEventDuringMatch() {
        // given
        Node event = amountEvent(1);

        // when
        ChannelEvaluation evaluation =
                ChannelEvaluation.match(event, "event-1");
        event.properties("amount", new Node().value(BigInteger.TEN));
        Node firstRead = evaluation.event();
        firstRead.properties("amount", new Node().value(new BigInteger("20")));

        // then
        assertEquals(BigInteger.ONE, evaluation.event().get("/amount"));
        assertNotSame(firstRead, evaluation.event());
        assertEquals("event-1", evaluation.eventId());
    }

    @Test
    void shouldVerifyContracts10EvaluationHasOnlyMatchAndNoMatch() {
        // given
        ChannelEvaluation matched =
                ChannelEvaluation.match(amountEvent(4));

        // when
        boolean match = matched.matches();
        boolean noMatch = ChannelEvaluation.noMatch().matches();

        // then
        assertTrue(match);
        assertFalse(noMatch);
    }

    private static Node amountEvent(int amount) {
        return new Node().properties("amount", new Node().value(BigInteger.valueOf(amount)));
    }
}
