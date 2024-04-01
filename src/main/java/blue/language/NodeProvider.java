package blue.language;


import blue.language.model.Node;

import java.util.List;

public interface NodeProvider {
    List<Node> fetchByBlueId(String blueId);
}