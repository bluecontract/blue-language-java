package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;

import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;

final class SyntheticWorkflowProcessingFixture {

    static final String BASE_STEP_NAME = "Synthetic Workflow Step";
    static final String CONCRETE_STEP_NAME = "Synthetic Compute Step";

    final BasicNodeProvider provider = new BasicNodeProvider();
    final AtomicInteger handlerExecutions = new AtomicInteger();
    final String workflowBlueId;
    final String rootTypeBlueId;
    final Blue blue;
    final Node source;

    SyntheticWorkflowProcessingFixture() {
        Node baseStep = new Node().name(BASE_STEP_NAME);
        provider.addSingleNodes(baseStep);
        String baseStepBlueId = provider.getBlueIdByName(BASE_STEP_NAME);

        Node concreteStep = new Node()
                .name(CONCRETE_STEP_NAME)
                .type(reference(baseStepBlueId))
                .properties("expression", new Node().value("computed"));
        provider.addSingleNodes(concreteStep);
        String concreteStepBlueId = provider.getBlueIdByName(CONCRETE_STEP_NAME);

        Node baseContract = new Node()
                .name("Synthetic Contract")
                .type(reference(RuntimeBlueIds.HANDLER));
        provider.addSingleNodes(baseContract);
        String baseContractBlueId = provider.getBlueIdByName("Synthetic Contract");

        Node workflow = new Node()
                .name("Synthetic Sequential Workflow")
                .type(reference(baseContractBlueId))
                .properties("steps", new Node()
                        .type(reference(LIST_TYPE_BLUE_ID))
                        .itemType(reference(baseStepBlueId)));
        provider.addSingleNodes(workflow);
        workflowBlueId = provider.getBlueIdByName("Synthetic Sequential Workflow");

        Node rootType = new Node()
                .name("Synthetic Resolved Processing Document")
                .properties("materializedField", new Node().value("materialized"))
                .contracts(new Node()
                        .properties("lifecycle", new Node()
                                .type(reference(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)))
                        .properties("workflow", new Node()
                                .type(reference(workflowBlueId))
                                .properties("channel", new Node().value("lifecycle"))
                                .properties("event", new Node()
                                        .type(reference(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED)))
                                .properties("steps", new Node()
                                        .type(reference(LIST_TYPE_BLUE_ID))
                                        .items(new Node()
                                                .name("Compute-like step")
                                                .type(reference(concreteStepBlueId))))));
        provider.addSingleNodes(rootType);
        rootTypeBlueId = provider.getBlueIdByName("Synthetic Resolved Processing Document");

        blue = new Blue(provider);
        blue.registerExternalContractType(
                workflowBlueId, workflow, new SyntheticWorkflowProcessor(handlerExecutions));
        source = new Node()
                .type(reference(rootTypeBlueId))
                .properties("probe", new Node().value("before"));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    public static final class SyntheticWorkflow extends HandlerContract {
        private Node steps;

        public Node getSteps() {
            return steps;
        }

        public void setSteps(Node steps) {
            this.steps = steps;
        }
    }

    private static final class SyntheticWorkflowProcessor implements HandlerProcessor<SyntheticWorkflow> {
        private final AtomicInteger executions;

        private SyntheticWorkflowProcessor(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public Class<SyntheticWorkflow> contractType() {
            return SyntheticWorkflow.class;
        }

        @Override
        public void execute(SyntheticWorkflow contract, ProcessorExecutionContext context) {
            executions.incrementAndGet();
            context.applyPatch(JsonPatch.replace("/probe", new Node().value("after")));
        }
    }
}
