package blue.lang;

import blue.lang.graph.Node;
import blue.lang.model.BlueObject;

import java.util.List;

public class Blue {

    public Object resolve(BlueObject object) {
        return null;
    }

    public Node resolve(Node object, List<String> paths) {
        return null;
    }

    public Blue withKnowledgeProvider(KnowledgeProvider knowledgeProvider) {
        return this;
    }

    interface KnowledgeProvider {
        Node fetchByBlueId(String blueId);
        Node fetchByTypeName(String type);
    }

}
