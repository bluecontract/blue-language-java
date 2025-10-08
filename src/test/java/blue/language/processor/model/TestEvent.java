package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;

@TypeBlueId("TestEvent")
public class TestEvent {

    private String eventId;
    private Integer x;
    private Integer y;
    private String kind;

    public String getEventId() {
        return eventId;
    }

    public TestEvent eventId(String eventId) {
        this.eventId = eventId;
        return this;
    }

    public Integer getX() {
        return x;
    }

    public TestEvent x(Integer x) {
        this.x = x;
        return this;
    }

    public Integer getY() {
        return y;
    }

    public TestEvent y(Integer y) {
        this.y = y;
        return this;
    }

    public String getKind() {
        return kind;
    }

    public TestEvent kind(String kind) {
        this.kind = kind;
        return this;
    }

    public Node toNode() {
        Node node = new Node().type(new Node().blueId("TestEvent"));
        if (eventId != null) {
            node.properties("eventId", new Node().value(eventId));
        }
        if (x != null) {
            node.properties("x", new Node().value(x));
        }
        if (y != null) {
            node.properties("y", new Node().value(y));
        }
        if (kind != null) {
            node.properties("kind", new Node().value(kind));
        }
        return node;
    }
}
