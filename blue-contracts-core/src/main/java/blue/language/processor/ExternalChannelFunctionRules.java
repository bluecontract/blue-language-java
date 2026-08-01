package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared deterministic validation rules for External Channel functions. */
final class ExternalChannelFunctionRules {

    private static final GasSchedule PORTABLE_LIMITS =
            GasSchedule.contracts10();

    private ExternalChannelFunctionRules() {
    }

    static long portableLimit(String limit) {
        return PORTABLE_LIMITS.portableLimit(limit);
    }

    static List<String> immutableEffectiveContractKeys(
            List<String> supplied) {
        Set<String> unique = new LinkedHashSet<>();
        for (String key : Objects.requireNonNull(
                supplied, "effectiveContractKeys")) {
            if (key == null || key.isEmpty() || !unique.add(key)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate effective contract key: "
                                + key);
            }
        }
        long limit = portableLimit(
                GasScheduleConstants.PortableLimit
                        .EFFECTIVE_CONTRACTS_PER_SCOPE);
        if (unique.size() > limit) {
            throw new IllegalStateException(
                    "Same-scope effective contract key catalog exceeds "
                            + limit);
        }
        List<String> keys = new ArrayList<>(unique);
        keys.sort(ExternalOrderKey::compareTextCodePoints);
        return Collections.unmodifiableList(keys);
    }

    static List<String> immutableKeys(
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean preselects(
            ExternalChannelSubscriptionFunctions functions,
            ChannelContract channel,
            Node event,
            ExternalChannelFunctionContext context,
            List<String> channelKeys,
            List<String> eventKeys) {
        boolean contextualOverride = overridesExact(
                functions,
                "preselects",
                ChannelContract.class,
                Node.class,
                ExternalChannelFunctionContext.class);
        boolean contextFreeOverride = overridesExact(
                functions,
                "preselects",
                ChannelContract.class,
                Node.class);
        if (!contextualOverride && !contextFreeOverride) {
            Set<String> eventKeySet = new LinkedHashSet<>(eventKeys);
            for (String channelKey : channelKeys) {
                if (eventKeySet.contains(channelKey)) {
                    return true;
                }
            }
            return false;
        }
        if (!contextualOverride) {
            return functions.preselects(channel, event);
        }
        return functions.preselects(channel, event, context);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean accepts(
            ExternalChannelSubscriptionFunctions functions,
            ChannelContract channel,
            Node event,
            ExternalChannelFunctionContext context,
            boolean preselects) {
        boolean contextualOverride = overridesExact(
                functions,
                "accepts",
                ChannelContract.class,
                Node.class,
                ExternalChannelFunctionContext.class);
        boolean contextFreeOverride = overridesExact(
                functions,
                "accepts",
                ChannelContract.class,
                Node.class);
        if (!contextualOverride && !contextFreeOverride) {
            return preselects;
        }
        if (!contextualOverride) {
            return functions.accepts(channel, event);
        }
        return functions.accepts(channel, event, context);
    }

    static boolean overridesExact(
            ExternalChannelSubscriptionFunctions<?> functions,
            String name,
            Class<?>... parameterTypes) {
        final java.lang.reflect.Method method;
        try {
            method = functions.getClass().getMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "External Channel function signature is unavailable: "
                            + name,
                    exception);
        }
        return method.getDeclaringClass()
                != ExternalChannelSubscriptionFunctions.class;
    }

    static String immutableRoutingKey(
            String supplied,
            String label) {
        if (supplied == null || supplied.isEmpty()) {
            throw new IllegalStateException(
                    "External Channel " + label
                            + " key must be non-empty Text");
        }
        long codePoints = supplied.codePointCount(0, supplied.length());
        long codePointLimit = portableLimit(
                GasScheduleConstants.PortableLimit
                        .CONTRACT_KEY_CODE_POINTS);
        if (codePoints > codePointLimit) {
            throw new IllegalStateException(
                    "External Channel " + label
                            + " key exceeds contractKeyCodePoints portable "
                            + "limit " + codePointLimit + ": "
                            + codePoints);
        }
        long utf8Bytes = supplied.getBytes(StandardCharsets.UTF_8).length;
        long utf8Limit = portableLimit(
                GasScheduleConstants.PortableLimit
                        .CONTRACT_KEY_UTF8_BYTES);
        if (utf8Bytes > utf8Limit) {
            throw new IllegalStateException(
                    "External Channel " + label
                            + " key exceeds contractKeyUtf8Bytes portable "
                            + "limit " + utf8Limit + ": "
                            + utf8Bytes);
        }
        return supplied;
    }
}
