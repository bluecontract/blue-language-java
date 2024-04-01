package blue.language.ref;

import blue.language.model.Node;

public interface RefBasedEnricher {
    boolean enrich(Node node, String ref);
}
