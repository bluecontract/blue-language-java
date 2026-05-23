package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.RecordDocumentUpdate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecordDocumentUpdateContractProcessor implements HandlerProcessor<RecordDocumentUpdate> {

    private final List<String> paths = new ArrayList<>();

    @Override
    public Class<RecordDocumentUpdate> contractType() {
        return RecordDocumentUpdate.class;
    }

    @Override
    public void execute(RecordDocumentUpdate contract, ProcessorExecutionContext context) {
        Node path = context.event().getProperties().get("path");
        paths.add(path != null ? String.valueOf(path.getValue()) : null);
    }

    public List<String> paths() {
        return Collections.unmodifiableList(paths);
    }
}
