package blue.lang.processor;

import blue.lang.Resolver;
import blue.lang.Node;
import blue.lang.NodeProcessor;

import java.util.HashMap;
import java.util.Map;

public class BlueIdResolver implements NodeProcessor {
    @Override
    public void process(Node target, Node source, Resolver resolver) {
        String blueId = source.getBlueId();
        if (blueId == null)
            return;

        Map<String, Node> nodes = new HashMap<>();
        nodes.put("FqJGnvANURarXp5k4Y6D4b8f9GR6ZnwDr5n3dGTK1111", new Node().name("found1")
                .properties("abc", new Node().blueId("FqJGnvANURarXp5k4Y6D4b8f9GR6ZnwDr5n3dGTK2222")));
        nodes.put("FqJGnvANURarXp5k4Y6D4b8f9GR6ZnwDr5n3dGTK2222", new Node().name("found2").value("44"));
        nodes.put("FqJGnvANURarXp5k4Y6D4b8f9GR6ZnwDr5n3dGTK3333", new Node().name("found1")
                .properties("abc", new Node().name("found2").value("44")));

        Node resolved = nodes.get(blueId);
        if (resolved == null) {
            target.blueId(blueId);
            return;
        }

        source.blueId(null)
                .name(resolved.getName())
                .type(resolved.getType())
                .value(resolved.getValue())
                .items(resolved.getItems())
                .properties(resolved.getProperties());
    }
}