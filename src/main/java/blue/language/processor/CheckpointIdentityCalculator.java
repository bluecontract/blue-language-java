package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

final class CheckpointIdentityCalculator {

    private CheckpointIdentityCalculator() {
    }

    static String identity(Node event) {
        return identity(event, null);
    }

    static String identity(Node event, Blue blue) {
        if (event == null) {
            return null;
        }
        try {
            return BlueIdCalculator.calculateBlueId(event);
        } catch (RuntimeException directFailure) {
            if (blue == null) {
                throw new IllegalStateException(
                        "Checkpoint event identity requires valid BlueId Input or a Blue canonicalization context",
                        directFailure);
            }
            try {
                return blue.calculateSemanticBlueId(event.clone());
            } catch (RuntimeException semanticFailure) {
                return ProcessorEngine.canonicalSignature(event.clone());
            }
        }
    }
}
