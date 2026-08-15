package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.application.PresentationActionOffer;
import io.github.gmcnicol.kernel.application.PresentationEnvelope;
import io.github.gmcnicol.kernel.presentationpack.PresentationResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded A2UI v0.9.1/basic adapter. Breaking protocol or catalogue support is added as a new
 * versioned adapter; these accepted identifiers stay immutable.
 */
final class CrmA2uiAdapter {

    static final String PROTOCOL = "v0.9.1";
    static final String CATALOGUE = "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json";
    private static final Set<String> COMPONENTS = Set.of("Column", "Text", "Button");
    private static final Set<String> RESERVED_PAYLOAD = Set.of("intentId", "payloadType", "payloadVersion");
    private static final Pattern PAYLOAD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    private static final int MAX_COMPONENTS = 64;
    private static final int MAX_DATA_CHARS = 16_384;
    private static final int MAX_TEXT_CHARS = 4_096;
    private static final int MAX_OUTPUT_BYTES = 65_536;

    private final ObjectMapper json;
    private final ObservationRegistry observations;

    CrmA2uiAdapter(ObjectMapper json, ObservationRegistry observations) {
        this.json = json;
        this.observations = observations;
    }

    PresentationResult render(PresentationEnvelope envelope, String source) {
        Observation observation;
        try {
            observation = Observation.start("kernel.presentation.rendering", observations);
        } catch (RuntimeException exporterFailure) {
            return renderValidated(envelope, source);
        }
        try {
            return renderValidated(envelope, source);
        } catch (RuntimeException businessFailure) {
            safe(() -> observation.error(businessFailure));
            throw businessFailure;
        } finally {
            safe(observation::stop);
        }
    }

    private PresentationResult renderValidated(PresentationEnvelope envelope, String source) {
        JsonNode messages = read(source);
        if (!messages.isArray() || messages.size() != 3) fail();

        String surface = createSurface(messages.get(0));
        Map<String, JsonNode> components = updateComponents(messages.get(1), surface);
        JsonNode data = updateData(messages.get(2), surface);
        Graph graph = validateGraph(components, envelope, data);
        String html = render(graph.root(), components, data, envelope, new HashSet<>());
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) fail();
        return new PresentationResult(html, patch(html), graph.offers());
    }

    private static void safe(Runnable signal) {
        try {
            signal.run();
        } catch (RuntimeException ignored) {
            // Presentation remains available when telemetry is unavailable.
        }
    }

    private JsonNode read(String source) {
        if (source == null || source.length() > MAX_DATA_CHARS * 2) fail();
        try {
            return json.readTree(source);
        } catch (JacksonException exception) {
            throw new InvalidSurface();
        }
    }

    private static String createSurface(JsonNode message) {
        JsonNode payload = operation(message, "createSurface");
        exact(payload, "surfaceId", "catalogId");
        String surface = text(payload, "surfaceId");
        if (!CATALOGUE.equals(text(payload, "catalogId"))) fail();
        return surface;
    }

    private static Map<String, JsonNode> updateComponents(JsonNode message, String surface) {
        JsonNode payload = operation(message, "updateComponents");
        exact(payload, "surfaceId", "components");
        sameSurface(payload, surface);
        JsonNode values = payload.get("components");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > MAX_COMPONENTS) fail();
        Map<String, JsonNode> components = new LinkedHashMap<>();
        for (JsonNode component : values) {
            if (!component.isObject()) fail();
            String id = text(component, "id");
            String type = text(component, "component");
            if (id.length() > 128 || !COMPONENTS.contains(type) || components.putIfAbsent(id, component) != null) fail();
        }
        return components;
    }

    private static JsonNode updateData(JsonNode message, String surface) {
        JsonNode payload = operation(message, "updateDataModel");
        exact(payload, "surfaceId", "path", "value");
        sameSurface(payload, surface);
        JsonNode data = payload.get("value");
        if (!"/".equals(text(payload, "path")) || data == null || !data.isObject()
                || data.toString().length() > MAX_DATA_CHARS) fail();
        return data;
    }

    private static JsonNode operation(JsonNode message, String name) {
        if (message == null || !message.isObject() || message.size() != 2
                || !PROTOCOL.equals(text(message, "version"))) fail();
        JsonNode payload = message.get(name);
        if (payload == null || !payload.isObject()) fail();
        return payload;
    }

    private static Graph validateGraph(
            Map<String, JsonNode> components, PresentationEnvelope envelope, JsonNode data) {
        Set<String> referenced = new HashSet<>();
        Set<UUID> offers = new HashSet<>();
        var budget = new OutputBudget();
        for (var entry : components.entrySet()) {
            JsonNode component = entry.getValue();
            switch (text(component, "component")) {
                case "Column" -> validateColumn(component, referenced);
                case "Text" -> validateText(component, data, budget);
                case "Button" -> offers.add(validateButton(component, referenced, envelope, data, budget));
                default -> fail();
            }
        }
        if (!components.keySet().containsAll(referenced)) fail();
        if (!components.containsKey("root") || referenced.contains("root")) fail();
        Set<String> visited = new HashSet<>();
        visit("root", components, new HashSet<>(), visited, false);
        if (visited.size() != components.size()) fail();
        return new Graph("root", offers);
    }

    private static void validateColumn(JsonNode component, Set<String> referenced) {
        exact(component, "id", "component", "children");
        JsonNode children = component.get("children");
        if (children == null || !children.isArray() || children.isEmpty()) fail();
        for (JsonNode child : children) addReference(referenced, scalarText(child));
    }

    private static void validateText(JsonNode component, JsonNode data, OutputBudget budget) {
        Set<String> fields = fields(component);
        if (!fields.equals(Set.of("id", "component", "text"))
                && !fields.equals(Set.of("id", "component", "text", "variant"))) fail();
        if (component.has("variant") && !"h2".equals(text(component, "variant"))) fail();
        budget.add(resolve(component.get("text"), data));
    }

    private static UUID validateButton(
            JsonNode component,
            Set<String> referenced,
            PresentationEnvelope envelope,
            JsonNode data,
            OutputBudget budget) {
        exact(component, "id", "component", "child", "action");
        addReference(referenced, text(component, "child"));
        JsonNode action = component.get("action");
        exact(action, "event");
        JsonNode event = action.get("event");
        exact(event, "name", "context");
        if (!"invokeActionOffer".equals(text(event, "name"))) fail();
        JsonNode context = event.get("context");
        Set<String> contextFields = fields(context);
        if (!contextFields.contains("actionOfferId") || contextFields.size() > 33) fail();
        UUID offerId;
        try {
            offerId = UUID.fromString(text(context, "actionOfferId"));
        } catch (IllegalArgumentException exception) {
            throw new InvalidSurface();
        }
        if (envelope.actionOffers().stream().noneMatch(offer -> offer.id().equals(offerId))) fail();
        context.properties().stream()
                .filter(entry -> !entry.getKey().equals("actionOfferId"))
                .forEach(entry -> {
                    if (!PAYLOAD_NAME.matcher(entry.getKey()).matches()
                            || RESERVED_PAYLOAD.contains(entry.getKey())) fail();
                    budget.add(resolve(entry.getValue(), data));
                });
        return offerId;
    }

    private static void visit(
            String id,
            Map<String, JsonNode> components,
            Set<String> ancestors,
            Set<String> visited,
            boolean insideButton) {
        if (!ancestors.add(id)) fail();
        visited.add(id);
        JsonNode component = components.get(id);
        if (component == null) fail();
        if ("Column".equals(text(component, "component"))) {
            component.get("children").forEach(child -> visit(scalarText(child), components,
                    new HashSet<>(ancestors), visited, insideButton));
        } else if ("Button".equals(text(component, "component"))) {
            if (insideButton) fail();
            visit(text(component, "child"), components, new HashSet<>(ancestors), visited, true);
        }
    }

    private static String render(
            String id,
            Map<String, JsonNode> components,
            JsonNode data,
            PresentationEnvelope envelope,
            Set<String> ancestors) {
        if (!ancestors.add(id)) fail();
        JsonNode component = components.get(id);
        return switch (text(component, "component")) {
            case "Column" -> "<section id=\"" + escape(id) + "\" class=\"a2ui-column\">"
                    + joinChildren(component.get("children"), components, data, envelope, ancestors) + "</section>";
            case "Text" -> renderText(id, component, data);
            case "Button" -> renderButton(id, component, components, data, envelope, ancestors);
            default -> throw new InvalidSurface();
        };
    }

    private static String joinChildren(
            JsonNode children,
            Map<String, JsonNode> components,
            JsonNode data,
            PresentationEnvelope envelope,
            Set<String> ancestors) {
        var html = new StringBuilder();
        children.forEach(child -> html.append(render(
                scalarText(child), components, data, envelope, new HashSet<>(ancestors))));
        return html.toString();
    }

    private static String renderText(String id, JsonNode component, JsonNode data) {
        String tag = component.has("variant") ? "h2" : "p";
        return "<" + tag + " id=\"" + escape(id) + "\">" + escape(resolve(component.get("text"), data))
                + "</" + tag + ">";
    }

    private static String renderButton(
            String id,
            JsonNode component,
            Map<String, JsonNode> components,
            JsonNode data,
            PresentationEnvelope envelope,
            Set<String> ancestors) {
        JsonNode context = component.get("action").get("event").get("context");
        UUID offerId = UUID.fromString(text(context, "actionOfferId"));
        PresentationActionOffer offer = envelope.actionOffers().stream()
                .filter(candidate -> candidate.id().equals(offerId)).findFirst().orElseThrow(InvalidSurface::new);
        StringBuilder fields = new StringBuilder();
        context.properties().stream()
                .filter(entry -> !entry.getKey().equals("actionOfferId"))
                .forEach(entry -> fields.append(
                        "<input type=\"hidden\" name=\"" + escape(entry.getKey()) + "\" value=\""
                                + escape(resolve(entry.getValue(), data)) + "\">"));
        return "<form method=\"post\" action=\"/presentation/intents/" + offerId
                + "\" data-on:submit=\"@post('/presentation/intents/" + offerId
                + "', {contentType: 'form'})\"><input type=\"hidden\" name=\"intentId\" value=\""
                + UUID.randomUUID() + "\"><input type=\"hidden\" name=\"payloadType\" value=\""
                + escape(offer.inputType()) + "\"><input type=\"hidden\" name=\"payloadVersion\" value=\"2\">"
                + fields + "<button id=\"" + escape(id) + "\" type=\"submit\">"
                + render(text(component, "child"), components, data, envelope, new HashSet<>(ancestors))
                + "</button></form>";
    }

    private static String resolve(JsonNode value, JsonNode data) {
        if (value == null) fail();
        JsonNode resolved;
        if (value.isTextual()) {
            resolved = value;
        } else {
            exact(value, "path");
            String path = text(value, "path");
            if (!path.startsWith("/") || path.length() > 512) fail();
            resolved = data.at(path);
        }
        if (!resolved.isValueNode() || resolved.isNull()) fail();
        String result = resolved.asText();
        if (result.length() > MAX_TEXT_CHARS) fail();
        return result;
    }

    private static void addReference(Set<String> referenced, String id) {
        if (!referenced.add(id)) fail();
    }

    private static void sameSurface(JsonNode payload, String surface) {
        if (!surface.equals(text(payload, "surfaceId"))) fail();
    }

    private static String text(JsonNode node, String field) {
        return scalarText(node == null ? null : node.get(field));
    }

    private static String scalarText(JsonNode node) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) fail();
        return node.textValue();
    }

    private static void exact(JsonNode node, String... expected) {
        if (node == null || !node.isObject() || !fields(node).equals(Set.of(expected))) fail();
    }

    private static Set<String> fields(JsonNode node) {
        if (node == null || !node.isObject()) fail();
        Set<String> fields = new HashSet<>();
        fields.addAll(node.propertyNames());
        return fields;
    }

    private static String patch(String html) {
        return "event: datastar-patch-elements\ndata: elements "
                + html.replace("\n", "\ndata: elements ") + "\n\n";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value).replace("\r", "&#13;").replace("\n", "&#10;");
    }

    private static void fail() {
        throw new InvalidSurface();
    }

    static final class InvalidSurface extends IllegalArgumentException {
        InvalidSurface() {
            super("Invalid A2UI surface");
        }
    }

    private record Graph(String root, Set<UUID> offers) {
        private Graph {
            offers = Set.copyOf(offers);
        }
    }

    private static final class OutputBudget {
        private int bytes;

        void add(String value) {
            bytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_OUTPUT_BYTES) fail();
        }
    }
}
