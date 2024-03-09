package blue.lang.graph.ref;

import blue.lang.graph.Node;

public interface RefBasedEnricher {
    boolean enrich(Node node, String ref);
}
