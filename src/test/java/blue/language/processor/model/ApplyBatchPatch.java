package blue.language.processor.model;

import blue.language.model.TypeBlueId;

@TypeBlueId(ProcessorTestTypeBlueIds.APPLY_BATCH_PATCH)
public class ApplyBatchPatch extends HandlerContract {

    private boolean addUnsupportedContract;

    public boolean isAddUnsupportedContract() {
        return addUnsupportedContract;
    }

    public boolean getAddUnsupportedContract() {
        return addUnsupportedContract;
    }

    public void setAddUnsupportedContract(boolean addUnsupportedContract) {
        this.addUnsupportedContract = addUnsupportedContract;
    }
}
