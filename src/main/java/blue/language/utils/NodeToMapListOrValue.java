package blue.language.utils;

import blue.language.model.Node;

/**
 * @deprecated Node wire projection is owned by
 * {@link blue.language.model.wire.NodeWireForm}.
 */
@Deprecated
public class NodeToMapListOrValue {

    public enum Strategy {
        OFFICIAL,
        SIMPLE
    }

    /** Creates the legacy wire projection facade. */
    public NodeToMapListOrValue() {
    }

    public static Object get(Node node) {
        return blue.language.model.wire.NodeWireForm.get(node);
    }

    public static Object get(Node node, Strategy strategy) {
        return blue.language.model.wire.NodeWireForm.get(
                node,
                strategy == Strategy.SIMPLE
                        ? blue.language.model.wire.NodeWireForm.Strategy.SIMPLE
                        : blue.language.model.wire.NodeWireForm.Strategy.OFFICIAL);
    }
}
