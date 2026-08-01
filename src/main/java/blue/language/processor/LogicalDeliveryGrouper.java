package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Coalesces equivalent accepted sources without transferring source
 * checkpoint ownership to their shared handler Channel.
 */
final class LogicalDeliveryGrouper {

    List<List<ChannelRunner.ExternalClassification>> group(
            List<ChannelRunner.ExternalClassification> classifications) {
        if (classifications == null || classifications.isEmpty()) {
            return Collections.emptyList();
        }
        Map<DeliveryKey, List<ChannelRunner.ExternalClassification>> groups =
                new LinkedHashMap<>();
        Map<LogicalKey, DeliveryKey> shapeByLogicalDelivery =
                new LinkedHashMap<>();
        for (ChannelRunner.ExternalClassification classification
                : classifications) {
            if (classification == null || !classification.acceptedNew()) {
                continue;
            }
            DeliveryKey key = DeliveryKey.from(classification);
            LogicalKey logicalKey = LogicalKey.from(classification);
            DeliveryKey previous = shapeByLogicalDelivery.putIfAbsent(
                    logicalKey, key);
            if (previous != null && !previous.equals(key)) {
                throw inconsistent(classification);
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(classification);
        }
        List<List<ChannelRunner.ExternalClassification>> result =
                new ArrayList<>();
        for (List<ChannelRunner.ExternalClassification> group
                : groups.values()) {
            result.add(Collections.unmodifiableList(
                    new ArrayList<>(group)));
        }
        return Collections.unmodifiableList(result);
    }

    ChannelRunner.ExternalClassification requireCoherent(
            List<ChannelRunner.ExternalClassification> classifications) {
        if (classifications == null || classifications.isEmpty()) {
            throw new IllegalArgumentException(
                    "Logical delivery group must not be empty");
        }
        ChannelRunner.ExternalClassification first = classifications.get(0);
        if (first == null || !first.acceptedNew()) {
            throw new IllegalArgumentException(
                    "Logical delivery group requires accepted-new "
                            + "classifications");
        }
        DeliveryKey expected = DeliveryKey.from(first);
        for (ChannelRunner.ExternalClassification classification
                : classifications) {
            if (classification == null
                    || !classification.acceptedNew()
                    || !expected.equals(DeliveryKey.from(classification))) {
                throw inconsistent(first);
            }
        }
        return first;
    }

    private IllegalArgumentException inconsistent(
            ChannelRunner.ExternalClassification classification) {
        return new IllegalArgumentException(
                "Logical delivery group is inconsistent at "
                        + classification.scopePath() + "/"
                        + classification.logicalDeliveryKey());
    }

    private static final class LogicalKey {
        private final String scopePath;
        private final String logicalDeliveryKey;

        private LogicalKey(String scopePath, String logicalDeliveryKey) {
            this.scopePath = scopePath;
            this.logicalDeliveryKey = logicalDeliveryKey;
        }

        static LogicalKey from(
                ChannelRunner.ExternalClassification classification) {
            return new LogicalKey(
                    classification.scopePath(),
                    classification.logicalDeliveryKey());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LogicalKey)) {
                return false;
            }
            LogicalKey that = (LogicalKey) other;
            return scopePath.equals(that.scopePath)
                    && logicalDeliveryKey.equals(that.logicalDeliveryKey);
        }

        @Override
        public int hashCode() {
            return 31 * scopePath.hashCode()
                    + logicalDeliveryKey.hashCode();
        }
    }

    private static final class DeliveryKey {
        private final LogicalKey logicalKey;
        private final String handlerChannelKey;
        private final String payloadBlueId;

        private DeliveryKey(
                LogicalKey logicalKey,
                String handlerChannelKey,
                String payloadBlueId) {
            this.logicalKey = logicalKey;
            this.handlerChannelKey = handlerChannelKey;
            this.payloadBlueId = payloadBlueId;
        }

        static DeliveryKey from(
                ChannelRunner.ExternalClassification classification) {
            return new DeliveryKey(
                    LogicalKey.from(classification),
                    Objects.requireNonNull(
                            classification.handlerChannelKey(),
                            "handlerChannelKey"),
                    Objects.requireNonNull(
                            classification.payloadBlueId(),
                            "payloadBlueId"));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DeliveryKey)) {
                return false;
            }
            DeliveryKey that = (DeliveryKey) other;
            return logicalKey.equals(that.logicalKey)
                    && handlerChannelKey.equals(that.handlerChannelKey)
                    && payloadBlueId.equals(that.payloadBlueId);
        }

        @Override
        public int hashCode() {
            int result = logicalKey.hashCode();
            result = 31 * result + handlerChannelKey.hashCode();
            return 31 * result + payloadBlueId.hashCode();
        }
    }
}
