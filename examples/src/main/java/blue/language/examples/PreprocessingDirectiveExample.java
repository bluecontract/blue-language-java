package blue.language.examples;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.registry.BootstrapProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static blue.language.model.wire.BlueLanguageConstants.BLUE_DIRECTIVE_IMPORTS;
import static blue.language.model.wire.BlueLanguageConstants.BLUE_DIRECTIVE_TRANSFORMATIONS;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

/** Demonstrates imports plus two deterministic transformations in declaration order. */
public final class PreprocessingDirectiveExample {

    private static final String MESSAGE_ALIAS = "Message";
    private static final String FIRST_STEP = "first";
    private static final String SECOND_STEP = "second";
    private static final String FIRST_SUFFIX = "-first";
    private static final String SECOND_SUFFIX = "-second";
    private static final String INITIAL_VALUE = "start";
    private static final String EXPECTED_VALUE = "start-first-second";

    private PreprocessingDirectiveExample() {
    }

    /** Resolves the complete directive, removes it, runs both steps, then normalizes. */
    public static Result run() {
        Node firstType = new Node().name("Append first preprocessing suffix");
        Node secondType = new Node().name("Append second preprocessing suffix");
        String firstTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(firstType);
        String secondTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(secondType);

        Map<String, Node> customTypes = new LinkedHashMap<>();
        customTypes.put(firstTypeBlueId, firstType);
        customTypes.put(secondTypeBlueId, secondType);
        NodeProvider customTypeProvider = requestedBlueId ->
                ExampleSupport.lookup(customTypes, requestedBlueId);
        NodeProvider completeProvider = new SequentialNodeProvider(
                BootstrapProvider.INSTANCE, customTypeProvider);

        List<String> executionOrder = new ArrayList<>();
        TransformationProcessor first = appendingProcessor(
                executionOrder, FIRST_STEP, FIRST_SUFFIX);
        TransformationProcessor second = appendingProcessor(
                executionOrder, SECOND_STEP, SECOND_SUFFIX);
        TransformationProcessorProvider processors = new TransformationProcessorProvider() {
            @Override
            public Optional<TransformationProcessor> getProcessor(
                    Node transformation) {
                Node type = transformation.getType();
                String typeBlueId = type == null ? null : type.getBlueId();
                if (firstTypeBlueId.equals(typeBlueId)) {
                    return Optional.of(first);
                }
                if (secondTypeBlueId.equals(typeBlueId)) {
                    return Optional.of(second);
                }
                return Optional.empty();
            }
        };

        Node directive = new Node().properties(
                BLUE_DIRECTIVE_IMPORTS,
                new Node().properties(
                        MESSAGE_ALIAS,
                        ExampleSupport.reference(TEXT_TYPE_BLUE_ID)),
                BLUE_DIRECTIVE_TRANSFORMATIONS,
                new Node().items(
                        new Node().type(ExampleSupport.reference(
                                firstTypeBlueId)),
                        new Node().type(ExampleSupport.reference(
                                secondTypeBlueId))));
        Node source = new Node()
                .blue(directive)
                .type(MESSAGE_ALIAS)
                .value(INITIAL_VALUE);
        Node preprocessed = new Preprocessor(processors, completeProvider)
                .preprocess(source);

        ExampleSupport.require(Arrays.asList(FIRST_STEP, SECOND_STEP)
                        .equals(executionOrder),
                "Transformations must run exactly once in declaration order");
        ExampleSupport.require(EXPECTED_VALUE.equals(preprocessed.getValue()),
                "Each transformation must observe the previous output");
        ExampleSupport.require(TEXT_TYPE_BLUE_ID.equals(
                        preprocessed.getType().getBlueId()),
                "Baseline alias substitution must run after transformations");
        ExampleSupport.require(preprocessed.getBlue() == null,
                "The directive must be removed before transformations execute");
        ExampleSupport.require(source.getBlue() != null
                        && INITIAL_VALUE.equals(source.getValue())
                        && MESSAGE_ALIAS.equals(source.getType().getValue()),
                "Preprocessing must not mutate the authored Source");
        return new Result(preprocessed, executionOrder, source);
    }

    private static TransformationProcessor appendingProcessor(
            final List<String> executionOrder,
            final String step,
            final String suffix) {
        return document -> {
            executionOrder.add(step);
            Node transformed = document.clone();
            transformed.value(String.valueOf(document.getValue()) + suffix);
            return transformed;
        };
    }

    /** Runs from a shell and prints the final normalized scalar. */
    public static void main(String[] args) {
        System.out.println(run().getPreprocessed().getValue());
    }

    /** Immutable result exposing the output, order, and unchanged Source. */
    public static final class Result {
        private final Node preprocessed;
        private final List<String> executionOrder;
        private final Node source;

        private Result(
                Node preprocessed,
                List<String> executionOrder,
                Node source) {
            this.preprocessed = preprocessed.clone();
            this.executionOrder = Collections.unmodifiableList(
                    new ArrayList<>(executionOrder));
            this.source = source.clone();
        }

        public Node getPreprocessed() {
            return preprocessed.clone();
        }

        public List<String> getExecutionOrder() {
            return executionOrder;
        }

        public Node getSource() {
            return source.clone();
        }
    }
}
