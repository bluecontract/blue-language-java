package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodePathAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class TypeGeneralizationPolicyResolver {

    private static final String DEFAULT_MODE = "nearest-valid";

    private TypeGeneralizationPolicyResolver() {
    }

    static void enforceScopeBoundary(String originScope, List<String> generatedPaths) {
        String normalizedOrigin = PointerUtils.normalizeScope(originScope);
        if ("/".equals(normalizedOrigin) || generatedPaths == null || generatedPaths.isEmpty()) {
            return;
        }
        for (String generatedPath : generatedPaths) {
            MetadataWrite write = MetadataWrite.from(generatedPath);
            if (write == null) {
                continue;
            }
            if (!PointerUtils.descendantOrEqual(write.nodePath, normalizedOrigin)) {
                throw new ProcessorFailureException(ProcessorErrorCategory.PatchBoundaryViolation,
                        "BoundaryViolation: embedded child patch cannot generalize parent scope");
            }
        }
    }

    static void enforce(ConformanceEngine conformanceEngine,
                        FrozenNode finalResolvedRoot,
                        List<String> generatedPaths) {
        enforce(conformanceEngine, finalResolvedRoot, generatedPaths, "/");
    }

    static void enforce(ConformanceEngine conformanceEngine,
                        FrozenNode finalResolvedRoot,
                        List<String> generatedPaths,
                        String originScope) {
        if (conformanceEngine == null || finalResolvedRoot == null
                || generatedPaths == null || generatedPaths.isEmpty()) {
            return;
        }
        Node root = finalResolvedRoot.toNode();
        String normalizedOrigin = PointerUtils.normalizeScope(originScope);
        Policy scopedPolicy = Policy.from(root, normalizedOrigin);
        Policy rootPolicy = "/".equals(normalizedOrigin) ? scopedPolicy : Policy.from(root, "/");
        for (String generatedPath : generatedPaths) {
            MetadataWrite write = MetadataWrite.from(generatedPath);
            if (write == null) {
                continue;
            }
            Policy policy = scopedPolicy.appliesTo(write.nodePath) ? scopedPolicy : rootPolicy;
            Rule rule = policy.ruleFor(write.nodePath);
            String mode = rule != null && rule.mode != null ? rule.mode : policy.defaultMode;
            if ("reject".equals(mode)) {
                throw new ProcessorFailureException(ProcessorErrorCategory.TypeGeneralizationFailure,
                        "GeneralizationRejected: type generalization policy rejects " + write.nodePath);
            }
            String floor = rule != null ? rule.mustRemainSubtypeOf : null;
            if (floor == null) {
                continue;
            }
            String generatedType = metadataBlueId(root, generatedPath);
            boolean withinFloor = generatedType != null
                    && (Objects.equals(generatedType, floor)
                    || conformanceEngine.isSubtypeOf(generatedType, floor));
            if (!withinFloor) {
                throw new ProcessorFailureException(ProcessorErrorCategory.TypeGeneralizationFailure,
                        "GeneralizationRejected: type generalization would cross policy floor");
            }
        }
    }

    private static String metadataBlueId(Node root, String pointer) {
        Node node = nodeAt(root, pointer);
        return node != null ? node.getBlueId() : null;
    }

    private static Node nodeAt(Node root, String pointer) {
        if (root == null) {
            return null;
        }
        try {
            return NodePathAccessor.getNode(root, pointer);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final class Policy {
        private final boolean present;
        private final String scope;
        private final String defaultMode;
        private final List<Rule> rules;

        private Policy(boolean present, String scope, String defaultMode, List<Rule> rules) {
            this.present = present;
            this.scope = scope;
            this.defaultMode = defaultMode != null ? defaultMode : DEFAULT_MODE;
            this.rules = rules;
        }

        private static Policy from(Node root, String scope) {
            String normalizedScope = PointerUtils.normalizeScope(scope);
            String markerPath = PointerUtils.resolvePointer(normalizedScope, "/contracts/generalization");
            Node marker = nodeAt(root, markerPath);
            if (marker == null) {
                return new Policy(false, normalizedScope, DEFAULT_MODE, java.util.Collections.emptyList());
            }
            String defaultMode = textField(marker, "defaultMode");
            Node rulesNode = field(marker, "rules");
            List<Rule> rules = new ArrayList<>();
            if (rulesNode != null && rulesNode.getItems() != null) {
                for (Node item : rulesNode.getItems()) {
                    String path = textField(item, "path");
                    if (path != null) {
                        rules.add(new Rule(PointerUtils.resolvePointer(normalizedScope, path),
                                textField(item, "mode"),
                                blueIdField(item, "mustRemainSubtypeOf")));
                    }
                }
            }
            return new Policy(true, normalizedScope, defaultMode, rules);
        }

        private boolean appliesTo(String pointer) {
            return present && PointerUtils.descendantOrEqual(pointer, scope);
        }

        private Rule ruleFor(String pointer) {
            Rule best = null;
            for (Rule rule : rules) {
                if (!PointerUtils.descendantOrEqual(pointer, rule.path)) {
                    continue;
                }
                if (best == null || rule.path.length() > best.path.length()) {
                    best = rule;
                }
            }
            return best;
        }
    }

    private static final class Rule {
        private final String path;
        private final String mode;
        private final String mustRemainSubtypeOf;

        private Rule(String path, String mode, String mustRemainSubtypeOf) {
            this.path = path;
            this.mode = mode;
            this.mustRemainSubtypeOf = mustRemainSubtypeOf;
        }
    }

    private static final class MetadataWrite {
        private final String nodePath;

        private MetadataWrite(String nodePath) {
            this.nodePath = nodePath;
        }

        private static MetadataWrite from(String pointer) {
            List<String> segments = JsonPointer.split(PointerUtils.normalizePointer(pointer));
            if (segments.isEmpty()) {
                return null;
            }
            String last = segments.get(segments.size() - 1);
            if (!isMetadataField(last)) {
                return null;
            }
            List<String> nodeSegments = segments.subList(0, segments.size() - 1);
            return new MetadataWrite(JsonPointer.toPointer(nodeSegments));
        }

        private static boolean isMetadataField(String field) {
            return "type".equals(field)
                    || "itemType".equals(field)
                    || "keyType".equals(field)
                    || "valueType".equals(field);
        }
    }

    private static String textField(Node node, String key) {
        Node field = field(node, key);
        Object value = field != null ? field.getValue() : null;
        return value != null ? String.valueOf(value) : null;
    }

    private static String blueIdField(Node node, String key) {
        Node field = field(node, key);
        if (field == null) {
            return null;
        }
        if (field.getBlueId() != null) {
            return field.getBlueId();
        }
        Object value = field.getValue();
        if (value != null) {
            return String.valueOf(value);
        }
        Node nested = field(field, "blueId");
        Object nestedValue = nested != null ? nested.getValue() : null;
        return nestedValue != null ? String.valueOf(nestedValue) : null;
    }

    private static Node field(Node node, String key) {
        return node != null && node.getProperties() != null ? node.getProperties().get(key) : null;
    }
}
