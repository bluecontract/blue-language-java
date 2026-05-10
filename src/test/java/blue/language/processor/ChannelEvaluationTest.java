package blue.language.processor;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void matchDeliveriesTreatsNullOrEmptyOrOnlyNullAsNoMatch() {
        assertFalse(ChannelEvaluation.matchDeliveries(null).matches());
        assertFalse(ChannelEvaluation.matchDeliveries(Collections.emptyList()).matches());

        List<ChannelDelivery> onlyNulls = new ArrayList<>();
        onlyNulls.add(null);

        assertFalse(ChannelEvaluation.matchDeliveries(onlyNulls).matches());
    }

    @Test
    void matchDeliveriesFiltersNullEntriesAndDefensivelyCopiesDeliveries() {
        Node event = amountEvent(3);
        ChannelDelivery delivery = ChannelDelivery.of(event, "event-1", "checkpoint", null);
        List<ChannelDelivery> deliveries = new ArrayList<>();
        deliveries.add(null);
        deliveries.add(delivery);

        ChannelEvaluation evaluation = ChannelEvaluation.matchDeliveries(deliveries);
        deliveries.clear();
        event.properties("amount", new Node().value(BigInteger.TEN));
        Node firstRead = evaluation.deliveries().get(0).event();
        firstRead.properties("amount", new Node().value(new BigInteger("20")));

        assertTrue(evaluation.matches());
        assertEquals(1, evaluation.deliveries().size());
        assertEquals(BigInteger.valueOf(3), evaluation.deliveries().get(0).event().get("/amount"));
        assertThrows(UnsupportedOperationException.class, () -> evaluation.deliveries().add(delivery));
    }

    private static Node amountEvent(int amount) {
        return new Node().properties("amount", new Node().value(BigInteger.valueOf(amount)));
    }
}
