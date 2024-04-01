package blue.language.ref;

import blue.language.Node;

public interface RefBasedEnricher {
    boolean enrich(Node node, String ref);
}
