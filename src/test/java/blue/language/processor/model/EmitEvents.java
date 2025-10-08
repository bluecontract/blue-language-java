package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

import java.util.ArrayList;
import java.util.List;

@TypeBlueId("EmitEvents")
public class EmitEvents extends HandlerContract {

    private List<Node> events = new ArrayList<>();
    private String expectedKind;

    public List<Node> getEvents() {
        return events;
    }

    public EmitEvents events(List<Node> events) {
        this.events = events != null ? events : new ArrayList<>();
        return this;
    }

    public String getExpectedKind() {
        return expectedKind;
    }

    public EmitEvents expectedKind(String expectedKind) {
        this.expectedKind = expectedKind;
        return this;
    }

    public EmitEvents addEvent(Node event) {
        if (event != null) {
            events.add(event);
        }
        return this;
    }
}
