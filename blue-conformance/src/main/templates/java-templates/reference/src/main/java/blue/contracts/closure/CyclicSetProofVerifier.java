package blue.contracts.closure;
public interface CyclicSetProofVerifier {
    void verify(ComponentSnapshot component,Object completeProof);
    String implementationIdentity();
}
