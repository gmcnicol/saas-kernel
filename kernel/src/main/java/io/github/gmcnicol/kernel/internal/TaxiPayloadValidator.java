package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import lang.taxi.TaxiDocument;
import lang.taxi.types.Field;
import lang.taxi.types.PrimitiveType;

final class TaxiPayloadValidator {

    private final TaxiDocument taxi;

    TaxiPayloadValidator(TaxiDocument taxi) {
        this.taxi = taxi;
    }

    void validate(String actionId, CandidatePayload payload) {
        if (payload.version() != 1) {
            throw new IllegalArgumentException("Unsupported payload version");
        }
        int separator = actionId.lastIndexOf('.');
        if (separator < 1) {
            throw new IllegalArgumentException("Invalid Action ID");
        }
        var operation = taxi.service(actionId.substring(0, separator)).operation(actionId.substring(separator + 1));
        if (operation.getParameters().size() != 1
                || !operation.getParameterType(0).getQualifiedName().equals(payload.type())) {
            throw new IllegalArgumentException("Payload type does not match Action input");
        }
        var model = taxi.objectType(payload.type());
        var expectedNames = new HashSet<String>();
        for (Field field : model.getAllFields()) {
            expectedNames.add(field.getName());
            String value = payload.values().get(field.getName());
            if (value == null) {
                if (!field.getNullable()) {
                    throw new IllegalArgumentException("Missing payload field: " + field.getName());
                }
            } else {
                validatePrimitive(field.getType().getBasePrimitive(), value);
            }
        }
        if (!expectedNames.containsAll(payload.values().keySet())) {
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
