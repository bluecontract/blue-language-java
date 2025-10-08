package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.MutateEmbeddedPaths;

public class MutateEmbeddedPathsContractProcessor implements HandlerProcessor<MutateEmbeddedPaths> {

    @Override
    public Class<MutateEmbeddedPaths> contractType() {
        return MutateEmbeddedPaths.class;
    }

    @Override
    public void execute(MutateEmbeddedPaths contract, ProcessorExecutionContext context) {
        String embeddedListPointer = "/contracts/embedded/paths";
        // Remove the second embedded path (originally /b).
        context.applyPatch(JsonPatch.remove(context.resolvePointer(embeddedListPointer + "/1")));
        // Replace the first entry (/a) with /c so the next iteration processes /c.
        context.applyPatch(JsonPatch.replace(context.resolvePointer(embeddedListPointer + "/0"), new Node().value("/c")));
        // Remove the /b subtree so its initialization never runs.
        context.applyPatch(JsonPatch.remove(context.resolvePointer("/b")));
    }
}
