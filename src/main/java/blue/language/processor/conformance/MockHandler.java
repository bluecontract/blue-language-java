package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

@TypeBlueId(MockTypeBlueIds.MOCK_HANDLER)
public final class MockHandler extends HandlerContract {

    private Node result;

    public Node getResult() {
        return result;
    }

    public void setResult(Node result) {
        this.result = result;
    }
}
