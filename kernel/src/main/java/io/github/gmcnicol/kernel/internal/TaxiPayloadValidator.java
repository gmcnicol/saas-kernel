package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import lang.taxi.TaxiDocument;
import lang.taxi.types.Field;
import lang.taxi.types.PrimitiveType;

final class TaxiPayloadValidator {

    private final TaxiDocument taxi;
    private final Set<String> events;
    private static final java.util.Set<PrimitiveType> SUPPORTED = EnumSet.of(
            PrimitiveType.BOOLEAN,
            PrimitiveType.STRING,
            PrimitiveType.INTEGER,
            PrimitiveType.LONG,
            PrimitiveType.DECIMAL,
            PrimitiveType.LOCAL_DATE,
            PrimitiveType.TIME,
            PrimitiveType.DATE_TIME,
            PrimitiveType.INSTANT,
            PrimitiveType.DOUBLE);

    TaxiPayloadValidator(TaxiDocument taxi, Set<String> actions, Set<String> events) {
        this.taxi = taxi;
        this.events = Set.copyOf(events);
        actions.forEach(action -> {
            int separator = action.lastIndexOf('.');
            var operation = taxi.service(action.substring(0, separator)).operation(action.substring(separator + 1));
            if (operation.getParameters().size() != 1
                    || !(operation.getParameterType(0) instanceof lang.taxi.types.ObjectType input)
                    || input.getAllFields().stream().anyMatch(field ->
                            !field.getType().getInheritsFromPrimitive()
                                    || !SUPPORTED.contains(field.getType().getBasePrimitive()))) {
                throw new IllegalStateException(
                        "Action payloads must be single flat models of supported Taxi primitive fields: "
                                + operation.getQualifiedName());
            }
        });
    }

    void validate(String actionId, CandidatePayload payload) {
        int separator = actionId.lastIndexOf('.');
        if (separator < 1) {
            throw new IllegalArgumentException("Invalid Action ID");
        }
        var operation = taxi.service(actionId.substring(0, separator)).operation(actionId.substring(separator + 1));
        if (operation.getParameters().size() != 1
                || !operation.getParameterType(0).getQualifiedName().equals(payload.type())) {
            throw new IllegalArgumentException("Payload type does not match Action input");
        }
        validateModel(payload.type(), payload.values());
    }

    String inputType(String actionId) {
        int separator = actionId.lastIndexOf('.');
        return taxi.service(actionId.substring(0, separator))
                .operation(actionId.substring(separator + 1))
                .getParameterType(0)
                .getQualifiedName();
    }

    void validateEvent(String actionId, String type, int version, java.util.Map<String, String> values) {
        int separator = actionId.lastIndexOf('.');
        var operation = taxi.service(actionId.substring(0, separator)).operation(actionId.substring(separator + 1));
        if (!events.contains(type) || !operation.getReturnType().getQualifiedName().equals(type)) {
            throw new IllegalArgumentException("Unsupported Event type or version");
        }
        validateModel(type, values);
    }

    private void validateModel(String type, java.util.Map<String, String> values) {
        var model = taxi.objectType(type);
        var expectedNames = new HashSet<String>();
        for (Field field : model.getAllFields()) {
            expectedNames.add(field.getName());
            String value = values.get(field.getName());
            if (value == null) {
                if (!field.getNullable()) {
                    throw new IllegalArgumentException("Missing payload field: " + field.getName());
                }
            } else {
                validatePrimitive(field.getType().getBasePrimitive(), value);
            }
        }
        if (!expectedNames.containsAll(values.keySet())) {
            throw new IllegalArgumentException("Payload contains unknown fields");
        }
    }

    private static void validatePrimitive(PrimitiveType type, String value) {
        try {
            switch (type) {
                case STRING -> { }
                case BOOLEAN -> {
                    if (!value.equals("true") && !value.equals("false")) {
                        throw new IllegalArgumentException();
                    }
                }
                case INTEGER -> Integer.parseInt(value);
                case LONG -> Long.parseLong(value);
                case DECIMAL -> new BigDecimal(value);
                case DOUBLE -> Double.parseDouble(value);
                case LOCAL_DATE -> LocalDate.parse(value);
                case TIME -> LocalTime.parse(value);
                case DATE_TIME -> LocalDateTime.parse(value);
                case INSTANT -> Instant.parse(value);
                default -> throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Payload value does not match Taxi type " + type, exception);
        }
    }
}
