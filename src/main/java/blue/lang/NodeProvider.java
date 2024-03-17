package blue.lang;

@FunctionalInterface
public interface NodeProvider {
    Node fetchByBlueId(String blueId);
}