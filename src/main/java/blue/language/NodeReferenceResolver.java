package blue.language;

import blue.language.model.Node;

public interface NodeReferenceResolver {
    Node resolveNode(String reference);
}