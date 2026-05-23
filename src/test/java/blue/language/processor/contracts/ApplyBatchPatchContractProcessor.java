package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.ApplyBatchPatch;
import blue.language.processor.model.JsonPatch;

import java.util.Arrays;

public class ApplyBatchPatchContractProcessor implements HandlerProcessor<ApplyBatchPatch> {

    @Override
    public Class<ApplyBatchPatch> contractType() {
        return ApplyBatchPatch.class;
    }

    @Override
    public void execute(ApplyBatchPatch contract, ProcessorExecutionContext context) {
        context.applyPatches(Arrays.asList(
                JsonPatch.replace("/a", new Node().value("one")),
                JsonPatch.replace("/b", new Node().value("two"))
        ));
    }
}
