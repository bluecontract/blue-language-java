package blue.language.preprocess;

import blue.language.model.Node;

public interface TransformationProcessor {
    Node process(Node document);
}
