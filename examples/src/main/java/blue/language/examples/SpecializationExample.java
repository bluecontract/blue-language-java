package blue.language.examples;

import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

/** Creates a new typed node by specializing a type with an authored overlay. */
public final class SpecializationExample {

    private static final String MESSAGE = "hello";

    private SpecializationExample() {
    }

    /**
     * Specializes Text while demonstrating that specialization is not
     * expansion.
     *
     * @return detached specialization, its identity, and unchanged overlay
     */
    public static Result run() {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node type = ExampleSupport.reference(TEXT_TYPE_BLUE_ID);
            Node overlay = new Node().value(MESSAGE);

            Node specialization = language.graph().specialize(type, overlay);
            String specializationBlueId = language.identity()
                    .sourceDocumentBlueId(specialization);

            ExampleSupport.require(TEXT_TYPE_BLUE_ID.equals(
                            specialization.getType().getBlueId()),
                    "The specialization must retain the exact Text type");
            ExampleSupport.require(MESSAGE.equals(specialization.getValue()),
                    "The specialization must contain the overlay value");
            ExampleSupport.require(overlay.getType() == null,
                    "Specialization must not mutate the overlay");
            ExampleSupport.require(!TEXT_TYPE_BLUE_ID.equals(
                            specializationBlueId),
                    "The new specialized node must have its own identity");
            return new Result(specialization, specializationBlueId, overlay);
        }
    }

    /**
     * Runs from a shell and prints the new specialization identity.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        System.out.println(run().getSpecializationBlueId());
    }

    /** Immutable result for the specialization operation. */
    public static final class Result {
        private final Node specialization;
        private final String specializationBlueId;
        private final Node originalOverlay;

        private Result(
                Node specialization,
                String specializationBlueId,
                Node originalOverlay) {
            this.specialization = specialization.clone();
            this.specializationBlueId = specializationBlueId;
            this.originalOverlay = originalOverlay.clone();
        }

        /**
         * Returns a detached specialized node.
         *
         * @return specialization copy
         */
        public Node getSpecialization() {
            return specialization.clone();
        }

        /**
         * Returns the Source Document identity of the specialization.
         *
         * @return specialization BlueId
         */
        public String getSpecializationBlueId() {
            return specializationBlueId;
        }

        /**
         * Returns a detached copy of the unchanged overlay input.
         *
         * @return original overlay copy
         */
        public Node getOriginalOverlay() {
            return originalOverlay.clone();
        }
    }
}
