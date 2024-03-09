package blue.lang;

import blue.lang.graph.Node;

import java.util.List;

public class Blue {

    public Node resolve(Node object) {
        return null;
    }

    public Node resolve(Node object, List<String> paths) {
        return null;
    }

    public <T> T resolve(Node object, List<String> paths, Class<T> type) {
        return null;
    }

    public String getBlueId(Node node) {
        return null;
    }



    public static void main(String[] args) {
//        Blue blue = new Blue();
//        Node node1 = blue.resolve(null, List.of("a", "b/b1"));
//        String blueId = blue.getBlueId(node1);

        // blue id: ...

    }

    public Node normalize(Node node) {
        return null;
    }

    public String normalizeAndHash(Node node) {
        return null;
    }

    public Blue withKnowledgeProvider(KnowledgeProvider knowledgeProvider) {
        return this;
    }

    public Blue withFeature(KnowledgeProvider knowledgeProvider) {
        return this;
    }

    interface KnowledgeProvider {
        Node fetchByBlueId(String blueId);
        Node fetchByTypeName(String type);
    }

    // (process input document) -> (merge / graph) -> (output)

}
