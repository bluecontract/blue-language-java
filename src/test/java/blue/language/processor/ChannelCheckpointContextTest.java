package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.MarkerContract;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelCheckpointContextTest {

    @Test
    void factoryPreservesCheckpointFields() {
        MarkerContract marker = new TestMarker();
        Map<String, MarkerContract> markers = new LinkedHashMap<>();
        markers.put("checkpoint", marker);
        Node event = new Node().properties("timestamp", new Node().value(10));
        Node lastEvent = new Node().properties("timestamp", new Node().value(9));

        ChannelCheckpointContext context = ChannelCheckpointContext.of("/child",
                "inbox::owner",
                event,
                "current-signature",
                lastEvent,
                "last-signature",
                markers);

        assertEquals("/child", context.scopePath());
        assertEquals("inbox::owner", context.channelKey());
        assertEquals("current-signature", context.eventSignature());
        assertEquals("last-signature", context.lastEventSignature());
        assertSame(marker, context.markers().get("checkpoint"));
        assertEquals(BigInteger.TEN, context.event().get("/timestamp"));
        assertEquals(BigInteger.valueOf(9), context.lastEvent().get("/timestamp"));
    }

    @Test
    void factoryDefensivelyCopiesEventNodes() {
        Node event = new Node().properties("timestamp", new Node().value(10));
        Node lastEvent = new Node().properties("timestamp", new Node().value(9));

        ChannelCheckpointContext context = ChannelCheckpointContext.of("/",
                "channel",
                event,
                "current",
                lastEvent,
                "last",
                null);

        event.properties("timestamp", new Node().value(11));
        lastEvent.properties("timestamp", new Node().value(8));

        assertEquals(BigInteger.TEN, context.event().get("/timestamp"));
        assertEquals(BigInteger.valueOf(9), context.lastEvent().get("/timestamp"));

        Node contextEvent = context.event();
        Node contextLastEvent = context.lastEvent();
        contextEvent.properties("timestamp", new Node().value(12));
        contextLastEvent.properties("timestamp", new Node().value(7));

        assertEquals(BigInteger.TEN, context.event().get("/timestamp"));
        assertEquals(BigInteger.valueOf(9), context.lastEvent().get("/timestamp"));
    }

    @Test
    void factoryDefensivelyCopiesMarkerMap() {
        MarkerContract marker = new TestMarker();
        Map<String, MarkerContract> markers = new LinkedHashMap<>();
        markers.put("checkpoint", marker);

        ChannelCheckpointContext context = ChannelCheckpointContext.of("/",
                "channel",
                null,
                null,
                null,
                null,
                markers);

        markers.clear();

        assertSame(marker, context.markers().get("checkpoint"));
        assertFalse(context.markers().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> context.markers().put("other", new TestMarker()));
    }

    private static final class TestMarker extends MarkerContract {
    }
}
