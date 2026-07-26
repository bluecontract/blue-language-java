package blue.language.processor;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelEvaluationTest {

    @Test
    void deliveryRequiresNonNullEvent() {
        assertThrows(NullPointerException.class,
                () -> ChannelDelivery.of(null, "event-1", "checkpoint", Boolean.TRUE));
    }

    @Test
    void deliveryDefensivelyCopiesEvent() {
        Node event = amountEvent(1);

        ChannelDelivery delivery = ChannelDelivery.of(event, "event-1", "checkpoint", Boolean.TRUE);
        event.properties("amount", new Node().value(BigInteger.TEN));
        Node firstRead = delivery.event();
        firstRead.properties("amount", new Node().value(new BigInteger("20")));

        assertEquals(BigInteger.ONE, delivery.event().get("/amount"));
        assertNotSame(firstRead, delivery.event());
    }

    @Test
    void callerAuthoredDeliveriesAreFailClosedCompatibilityOnly() {
        ChannelDelivery delivery = ChannelDelivery.of(
                amountEvent(4),
                "event-4",
                "source-checkpoint",
                Boolean.TRUE,
                "effective-channel",
                "logical-delivery");

        UnsupportedOperationException failure =
                assertThrows(UnsupportedOperationException.class,
                        () -> ChannelEvaluation.matchDeliveries(
                                Collections.singletonList(delivery)));

        assertEquals(
                "Caller-authored channel deliveries are not executable "
                        + "under Contracts 1.0",
                failure.getMessage());
        assertEquals(Collections.emptyList(),
                ChannelEvaluation.match(amountEvent(1)).deliveries());
    }

    private static Node amountEvent(int amount) {
        return new Node().properties("amount", new Node().value(BigInteger.valueOf(amount)));
    }
}
