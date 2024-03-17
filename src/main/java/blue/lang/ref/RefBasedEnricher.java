package blue.lang.ref;

import blue.lang.Node;

public interface RefBasedEnricher {
    boolean enrich(Node node, String ref);
}
