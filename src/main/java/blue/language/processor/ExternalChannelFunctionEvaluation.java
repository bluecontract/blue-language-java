package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Run-local result of the registered immutable External Channel functions.
 *
 * <p>Every evaluation is repeated from a fresh conversion of the frozen
 * effective contract. This makes function nondeterminism observable without
 * trusting either feeder-derived payload data or mutable converted contract
 * instances.</p>
 */
final class ExternalChannelFunctionEvaluation {

    private final List<String> channelKeys;
    private final List<String> eventKeys;
    private final boolean preselects;
    private final boolean accepts;
    private final String checkpointDomainBlueId;
    private final FrozenNode payload;
    private final String checkpointSubjectBlueId;

    private ExternalChannelFunctionEvaluation(
            List<String> channelKeys,
            List<String> eventKeys,
            boolean preselects,
            boolean accepts,
            String checkpointDomainBlueId,
            FrozenNode payload,
            String checkpointSubjectBlueId) {
        this.channelKeys = channelKeys;
        this.eventKeys = eventKeys;
        this.preselects = preselects;
        this.accepts = accepts;
        this.checkpointDomainBlueId = checkpointDomainBlueId;
        this.payload = payload;
        this.checkpointSubjectBlueId = checkpointSubjectBlueId;
    }

    static ExternalChannelFunctionEvaluation evaluate(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node exactEvent) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(exactEvent, "exactEvent");

        ExternalChannelFunctionEvaluation first =
                evaluateOnce(
                        registry, converter, bundle, snapshot, exactEvent);
        ExternalChannelFunctionEvaluation second =
                evaluateOnce(
                        registry, converter, bundle, snapshot, exactEvent);
        if (!first.sameResult(second)) {
            throw new IllegalStateException(
                    "External Channel functions are not deterministic at "
                            + snapshot.scopePath() + "/" + snapshot.key());
        }
        return first;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ExternalChannelFunctionEvaluation evaluateOnce(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot,
            Node exactEvent) {
        ChannelContract registrationProbe =
                freshChannel(converter, bundle, snapshot);
        ChannelProcessor processor = registry.lookupChannel(
                registrationProbe)
                .orElse(null);
        ExternalChannelSubscriptionFunctions functions =
                processor != null
                        ? processor.externalSubscriptionFunctions()
                        : null;
        if (functions == null) {
            throw new IllegalStateException(
                    "External Channel runtime type does not expose immutable "
                            + "PRESELECTS/ACCEPTS/PAYLOAD/"
                            + "CHECKPOINT_SUBJECT functions: "
                            + snapshot.effectiveTypeBlueId());
        }

        List<String> channelKeys = immutableKeys(
                functions.channelKeys(freshChannel(
                        converter, bundle, snapshot)),
                "channel");
        List<String> eventKeys = immutableKeys(
                functions.eventKeys(exactEvent.clone()), "event");
        boolean preselects =
                functions.preselects(
                        freshChannel(converter, bundle, snapshot),
                        exactEvent.clone());
        boolean accepts =
                functions.accepts(
                        freshChannel(converter, bundle, snapshot),
                        exactEvent.clone());
        String checkpointDomain = CheckpointDomain.derive(
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                functions.checkpointDomainDiscriminator(
                        freshChannel(converter, bundle, snapshot)));

        FrozenNode payload = null;
        String checkpointSubjectBlueId = null;
        if (accepts) {
            Node suppliedPayload =
                    functions.payload(
                            freshChannel(
                                    converter, bundle, snapshot),
                            exactEvent.clone());
            if (suppliedPayload == null) {
                throw new IllegalStateException(
                        "External Channel PAYLOAD returned no exact node at "
                                + snapshot.scopePath() + "/"
                                + snapshot.key());
            }
            payload = FrozenNode.fromResolvedNode(
                    suppliedPayload.clone());
            Node checkpointSubject = functions.checkpointSubject(
                    freshChannel(converter, bundle, snapshot),
                    exactEvent.clone(),
                    payload.toNode());
            if (checkpointSubject == null) {
                throw new IllegalStateException(
                        "External Channel CHECKPOINT_SUBJECT returned no "
                                + "exact node at " + snapshot.scopePath()
                                + "/" + snapshot.key());
            }
            try {
                checkpointSubjectBlueId =
                        BlueIdCalculator.calculateBlueId(
                                checkpointSubject.clone());
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "External Channel CHECKPOINT_SUBJECT is not exact "
                                + "BlueId Input at " + snapshot.scopePath()
                                + "/" + snapshot.key(),
                        exception);
            }
        }

        return new ExternalChannelFunctionEvaluation(
                channelKeys,
                eventKeys,
                preselects,
                accepts,
                checkpointDomain,
                payload,
                checkpointSubjectBlueId);
    }

    private static ChannelContract freshChannel(
            NodeToObjectConverter converter,
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot) {
        FrozenNode content = bundle.contractNode(snapshot.key());
        if (content == null) {
            throw new IllegalStateException(
                    "External Channel effective content is unavailable at "
                            + snapshot.scopePath() + "/" + snapshot.key());
        }
        Contract converted = converter.convertWithType(
                content.toNode().clone(), Contract.class, false);
        if (!(converted instanceof ChannelContract)) {
            throw new IllegalStateException(
                    "External Channel could not be converted at "
                            + snapshot.scopePath() + "/" + snapshot.key());
        }
        ChannelContract channel = (ChannelContract) converted;
        channel.setKey(snapshot.key());
        channel.setTypeBlueId(snapshot.effectiveTypeBlueId());
        return channel;
    }

    private static List<String> immutableKeys(
            List<String> supplied,
            String label) {
        if (supplied == null) {
            throw new IllegalStateException(
                    "External subscription " + label
                            + " key function returned no finite set");
        }
        List<String> copy = new ArrayList<>(supplied);
        Set<String> unique = new LinkedHashSet<>();
        for (String key : copy) {
            if (key == null || key.isEmpty() || !unique.add(key)) {
                throw new IllegalStateException(
                        "External subscription " + label
                                + " keys must be unique non-empty Text");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private boolean sameResult(
            ExternalChannelFunctionEvaluation other) {
        return channelKeys.equals(other.channelKeys)
                && eventKeys.equals(other.eventKeys)
                && preselects == other.preselects
                && accepts == other.accepts
                && checkpointDomainBlueId.equals(
                other.checkpointDomainBlueId)
                && Objects.equals(payloadBlueId(), other.payloadBlueId())
                && Objects.equals(
                checkpointSubjectBlueId,
                other.checkpointSubjectBlueId);
    }

    private String payloadBlueId() {
        return payload != null ? payload.blueId() : null;
    }

    List<String> channelKeys() {
        return channelKeys;
    }

    List<String> eventKeys() {
        return eventKeys;
    }

    boolean preselects() {
        return preselects;
    }

    boolean accepts() {
        return accepts;
    }

    String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    FrozenNode payload() {
        return payload;
    }

    String checkpointSubjectBlueId() {
        return checkpointSubjectBlueId;
    }
}
