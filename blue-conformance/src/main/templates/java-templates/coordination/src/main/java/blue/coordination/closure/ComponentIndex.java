package blue.coordination.closure;

import blue.contracts.closure.DocumentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable deterministic component and condensation index. */
public final class ComponentIndex {
    private final Map<DocumentId, ComponentId> componentByDocument;
    private final Map<ComponentId, ProcessingComponent> components;
    private final List<ComponentId> deepestFirstOrder;
    private final String identity;

    public ComponentIndex(
            Map<DocumentId, ComponentId> componentByDocument,
            Map<ComponentId, ProcessingComponent> components,
            List<ComponentId> deepestFirstOrder,
            String identity) {
        LinkedHashMap<DocumentId, ComponentId> documentCopy =
                new LinkedHashMap<DocumentId, ComponentId>();
        for (Map.Entry<DocumentId, ComponentId> entry
                : Objects.requireNonNull(
                        componentByDocument, "componentByDocument").entrySet()) {
            documentCopy.put(
                    Objects.requireNonNull(entry.getKey(), "documentId"),
                    Objects.requireNonNull(entry.getValue(), "componentId"));
        }
        this.componentByDocument = Collections.unmodifiableMap(documentCopy);
        LinkedHashMap<ComponentId, ProcessingComponent> componentCopy =
                new LinkedHashMap<ComponentId, ProcessingComponent>();
        for (Map.Entry<ComponentId, ProcessingComponent> entry
                : Objects.requireNonNull(components, "components").entrySet()) {
            ComponentId componentId = Objects.requireNonNull(
                    entry.getKey(), "component key");
            ProcessingComponent component = Objects.requireNonNull(
                    entry.getValue(), "component value");
            if (!componentId.equals(component.id())) {
                throw new IllegalArgumentException("component key mismatch");
            }
            componentCopy.put(componentId, component);
        }
        if (componentCopy.isEmpty()) {
            throw new IllegalArgumentException("components");
        }
        this.components = Collections.unmodifiableMap(componentCopy);
        ArrayList<ComponentId> orderCopy = new ArrayList<ComponentId>(
                Objects.requireNonNull(deepestFirstOrder, "deepestFirstOrder"));
        LinkedHashSet<ComponentId> orderSet = new LinkedHashSet<ComponentId>();
        for (ComponentId componentId : orderCopy) {
            if (!orderSet.add(Objects.requireNonNull(
                    componentId, "deepestFirstOrder item"))) {
                throw new IllegalArgumentException(
                        "duplicate component in deepestFirstOrder");
            }
        }
        this.deepestFirstOrder = Collections.unmodifiableList(orderCopy);
        this.identity = Objects.requireNonNull(identity, "identity");
        if (!orderSet.equals(this.components.keySet())) {
            throw new IllegalArgumentException("deepestFirstOrder must contain every component exactly once");
        }
        LinkedHashMap<DocumentId, ComponentId> derivedMembership =
                new LinkedHashMap<DocumentId, ComponentId>();
        for (Map.Entry<ComponentId, ProcessingComponent> entry
                : this.components.entrySet()) {
            for (DocumentId member : entry.getValue().orderedMembers()) {
                if (derivedMembership.put(member, entry.getKey()) != null) {
                    throw new IllegalArgumentException(
                            "document belongs to multiple components: " + member);
                }
            }
        }
        if (!derivedMembership.equals(this.componentByDocument)) {
            throw new IllegalArgumentException(
                    "componentByDocument must equal complete component membership");
        }
    }

    public ComponentId componentOf(DocumentId documentId) {
        ComponentId result = componentByDocument.get(documentId);
        if (result == null) {
            throw new IllegalArgumentException("unknown document " + documentId);
        }
        return result;
    }

    public ProcessingComponent component(ComponentId id) {
        ProcessingComponent result = components.get(id);
        if (result == null) {
            throw new IllegalArgumentException("unknown component " + id);
        }
        return result;
    }

    public Map<ComponentId, ProcessingComponent> components() {
        return components;
    }

    public Set<DocumentId> documentIds() {
        return componentByDocument.keySet();
    }

    public List<ComponentId> deepestFirstOrder() {
        return deepestFirstOrder;
    }

    public String identity() {
        return identity;
    }
}
