package blue.language.processor;

import blue.language.processor.util.ProcessorContractConstants;

import java.util.Objects;

/**
 * Read-only view of the channels frozen for one participating scope.
 *
 * <p>The catalog deliberately exposes bindings rather than processors. A
 * selected handler Channel contributes dispatch metadata only; external
 * acceptance and checkpoint ownership remain with the raw source Channel.</p>
 */
final class SameScopeChannelCatalog {

    private final ContractBundle bundle;

    SameScopeChannelCatalog(ContractBundle bundle) {
        this.bundle = Objects.requireNonNull(bundle, "bundle");
    }

    ContractBundle.ChannelBinding externalSource(String channelKey) {
        ContractBundle.ChannelBinding binding = bundle.channelBinding(
                channelKey);
        return binding != null
                && !ProcessorContractConstants.isProcessorManagedChannel(
                binding.contract())
                ? binding
                : null;
    }

    ContractBundle.ChannelBinding handlerTarget(String channelKey) {
        return bundle.channelBinding(channelKey);
    }
}
