package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds immutable dispatch snapshots without inventing an effective BlueId.
 *
 * <p>Headers are retained field-by-field, source contribution identities stay
 * in merge order, and executable bodies are represented only by their exact
 * authored identities and Source descriptors.</p>
 */
final class ContractSnapshotFactory {

    EffectiveContractSnapshot.Builder begin(
            String scopePath,
            String key,
            String effectiveTypeBlueId,
            int order,
            List<String> sourceContributions) {
        EffectiveContractSnapshot.Builder snapshot =
                EffectiveContractSnapshot.builder(scopePath, key)
                        .effectiveTypeBlueId(effectiveTypeBlueId)
                        .order(order);
        for (String contribution : sourceContributions) {
            snapshot.sourceContribution(contribution);
        }
        return snapshot;
    }

    void addHeaderFields(
            EffectiveContractSnapshot.Builder snapshot,
            FrozenNode contract,
            List<String> executableBodyFields) {
        if (contract == null
                || contract.getProperties() == null
                || contract.getProperties().isEmpty()) {
            return;
        }
        Set<String> executable = new LinkedHashSet<>(
                executableBodyFields != null
                        ? executableBodyFields
                        : Collections.<String>emptyList());
        List<String> names = new ArrayList<>(contract.getProperties().keySet());
        names.sort(ExternalOrderKey::compareTextCodePoints);
        for (String name : names) {
            if (!executable.contains(name)) {
                snapshot.headerField(name, contract.getProperties().get(name));
            }
        }
    }

    void addEventDispatch(
            EffectiveContractSnapshot.Builder snapshot,
            Node eventPattern) {
        if (eventPattern == null) {
            return;
        }
        String identity = FrozenNode.fromResolvedNode(eventPattern).blueId();
        snapshot.dispatchField(
                        EffectiveContractSnapshotConstants.DispatchField.EVENT,
                        identity)
                .deterministicDependency(identity);
    }

    void addExecutableBody(
            EffectiveContractSnapshot.Builder snapshot,
            String field,
            String scopePath,
            String contractKey,
            String contractTypeBlueId,
            ContractContributionResolver.BindingResolution binding) {
        Node exactBody = binding.exactExecutableBodies().get(field);
        if (exactBody == null) {
            return;
        }
        Node canonicalBody = exactBody.clone();
        MaterializationProvenance.clear(canonicalBody);
        String exactBodyBlueId = FrozenNode.fromNode(canonicalBody).blueId();
        ContractContributionResolver.ExecutableBodySource source =
                binding.executableBodySources().get(field);
        if (source == null) {
            throw new MustUnderstandFailureException(
                    "Cannot establish executable-body Source for contract '"
                            + contractKey
                            + "' field '"
                            + field
                            + "'",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        snapshot.executableBody(field, exactBodyBlueId)
                .executableBodySourceDescriptor(
                        field,
                        new ExecutableBodySourceDescriptor(
                                scopePath,
                                contractKey,
                                contractTypeBlueId,
                                field,
                                exactBodyBlueId,
                                binding.sourceContributions(),
                                source.owningContributionBlueId(),
                                source.sourcePointer(),
                                source.pureReference()));
    }
}
