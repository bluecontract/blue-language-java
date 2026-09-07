package blue.language.processor;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;
import java.util.Objects;

/**
 * Collects the exact Source contributions that form an effective contract.
 *
 * <p>The collector is deliberately separate from effective-header resolution:
 * it walks authored type contributions in ancestor-to-descendant order and
 * never assigns an identity to the merged effective result. Exact executable
 * bodies and their owning contribution descriptors travel with the resulting
 * binding so body materialization can remain lazy.</p>
 */
final class ContractContributionCollector {

    private final ContractContributionResolver resolver;

    ContractContributionCollector(NodeProvider provider) {
        this(provider, null);
    }

    ContractContributionCollector(
            NodeProvider provider,
            ProcessingSnapshotManager snapshotManager) {
        this.resolver = new ContractContributionResolver(
                provider,
                GasSchedule.contracts10(),
                snapshotManager);
    }

    void gasSchedule(GasSchedule gasSchedule) {
        resolver.gasSchedule(
                Objects.requireNonNull(gasSchedule, "gasSchedule"));
    }

    FrozenNode materializeVerifiedReference(FrozenNode reference) {
        return resolver.materializeVerifiedReference(reference);
    }

    FrozenNode materializeVerifiedHeader(
            FrozenNode contribution,
            Collection<String> executableBodyFields) {
        return resolver.materializeVerifiedHeader(
                contribution,
                executableBodyFields);
    }

    ContractContributionResolver.BindingResolution collect(
            Node selectedScope,
            FrozenNode effectiveScope,
            String contractKey,
            boolean effectiveContractExists,
            Collection<String> executableBodyFields) {
        return collect(
                selectedScope,
                effectiveScope,
                contractKey,
                effectiveContractExists,
                executableBodyFields,
                executableBodyFields);
    }

    ContractContributionResolver.BindingResolution collect(
            Node selectedScope,
            FrozenNode effectiveScope,
            String contractKey,
            boolean effectiveContractExists,
            Collection<String> exactSourceFields,
            Collection<String> executableBodyFields) {
        return collect(
                selectedScope,
                effectiveScope,
                contractKey,
                effectiveContractExists,
                exactSourceFields,
                executableBodyFields,
                null);
    }

    ContractContributionResolver.BindingResolution collect(
            Node selectedScope,
            FrozenNode effectiveScope,
            String contractKey,
            boolean effectiveContractExists,
            Collection<String> exactSourceFields,
            Collection<String> executableBodyFields,
            CanonicalContributionIdentityMemo identityMemo) {
        return resolver.resolveBinding(
                selectedScope,
                effectiveScope,
                contractKey,
                effectiveContractExists,
                exactSourceFields,
                executableBodyFields,
                identityMemo);
    }
}
