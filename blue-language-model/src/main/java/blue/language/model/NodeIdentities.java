package blue.language.model;

import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/** Resolves the single normative identity provider for model conveniences. */
public final class NodeIdentities {

    private NodeIdentities() {
    }

    /**
     * Calculates a derived identity through the installed Language provider.
     * Exactly one provider is required so classpath order cannot affect the
     * result.
     *
     * @param node node to identify
     * @return deterministic BlueId
     */
    public static String calculate(Node node) {
        return Holder.PROVIDER.calculate(node);
    }

    /**
     * Calculates an ordered sequence identity through the installed Language
     * provider.
     *
     * @param nodes ordered nodes to identify
     * @return deterministic list BlueId
     */
    public static String calculate(List<Node> nodes) {
        return Holder.PROVIDER.calculate(nodes);
    }

    private static final class Holder {
        private static final NodeIdentityProvider PROVIDER = loadProvider();

        private static NodeIdentityProvider loadProvider() {
            Iterator<NodeIdentityProvider> providers = ServiceLoader
                    .load(NodeIdentityProvider.class,
                            NodeIdentityProvider.class.getClassLoader())
                    .iterator();
            if (!providers.hasNext()) {
                throw new IllegalStateException(
                        "No NodeIdentityProvider is installed. Add the Blue Language core runtime to derive /blueId values.");
            }
            NodeIdentityProvider provider = providers.next();
            if (providers.hasNext()) {
                throw new IllegalStateException(
                        "Multiple NodeIdentityProvider implementations are installed; deterministic identity requires exactly one.");
            }
            return provider;
        }
    }
}
