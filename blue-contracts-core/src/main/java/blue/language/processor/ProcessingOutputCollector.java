package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Collects only Root-visible events in deterministic emission order. */
final class ProcessingOutputCollector {

    private final List<Node> rootEvents = new ArrayList<>();

    List<Node> rootEvents() {
        return rootEvents;
    }

    long nextRootEventCount() {
        return rootEvents.size() + 1L;
    }

    void recordRootEvent(Node event) {
        rootEvents.add(Objects.requireNonNull(event, "event"));
    }
}
