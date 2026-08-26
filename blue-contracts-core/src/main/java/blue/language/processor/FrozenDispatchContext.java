package blue.language.processor;

import blue.language.processor.model.DocumentUpdateChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable contract-routing surface accepted before one semantic mutation.
 *
 * <p>The current delivery uses only these bindings. The live participation
 * registry may be refreshed for later work without allowing a newly added
 * Channel to observe the update that created it, or retroactively removing a
 * Channel from an already accepted delivery.</p>
 */
final class FrozenDispatchContext {

    private static final FrozenDispatchContext EMPTY =
            new FrozenDispatchContext(
                    Collections.<String, ContractBundle>emptyMap());

    private final Map<String, ContractBundle> bundles;

    private FrozenDispatchContext(Map<String, ContractBundle> bundles) {
        this.bundles = Collections.unmodifiableMap(
                new LinkedHashMap<String, ContractBundle>(bundles));
    }

    static FrozenDispatchContext capture(
            ScopeParticipationRegistry participation) {
        return capture(participation, null, null);
    }

    static FrozenDispatchContext capture(
            ScopeParticipationRegistry participation,
            String sourceScopePath,
            ContractBundle sourceBundle) {
        Objects.requireNonNull(participation, "participation");
        LinkedHashMap<String, ContractBundle> captured =
                new LinkedHashMap<String, ContractBundle>();
        for (String scopePath : participation.scopePaths()) {
            ContractBundle bundle = participation.bundle(scopePath);
            if (bundle != null) {
                captured.put(
                        ProcessorEngine.normalizeScope(scopePath), bundle);
            }
        }
        if (sourceBundle != null) {
            captured.put(
                    ProcessorEngine.normalizeScope(
                            Objects.requireNonNull(
                                    sourceScopePath, "sourceScopePath")),
                    sourceBundle);
        }
        return new FrozenDispatchContext(captured);
    }

    static FrozenDispatchContext empty() {
        return EMPTY;
    }

    ContractBundle bundle(String scopePath) {
        return bundles.get(ProcessorEngine.normalizeScope(scopePath));
    }

    boolean participates(String scopePath) {
        return bundles.containsKey(
                ProcessorEngine.normalizeScope(scopePath));
    }

    List<ContractBundle.ChannelBinding> matchingDocumentUpdateChannels(
            String scopePath,
            String updatePath) {
        ContractBundle bundle = bundle(scopePath);
        if (bundle == null) {
            return Collections.emptyList();
        }
        List<ContractBundle.ChannelBinding> matching =
                new ArrayList<ContractBundle.ChannelBinding>();
        for (ContractBundle.ChannelBinding channel
                : bundle.channelsOfType(DocumentUpdateChannel.class)) {
            DocumentUpdateChannel documentUpdate =
                    (DocumentUpdateChannel) channel.contract();
            if (ProcessorEngine.matchesDocumentUpdate(
                    scopePath,
                    documentUpdate.getPath(),
                    updatePath)) {
                matching.add(channel);
            }
        }
        return Collections.unmodifiableList(matching);
    }
}
