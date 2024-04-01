package blue.language;


import java.util.List;

public interface NodeProvider {
    List<Node> fetchByBlueId(String blueId);
}