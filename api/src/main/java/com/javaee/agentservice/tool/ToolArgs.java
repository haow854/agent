package com.javaee.agentservice.tool;

import java.util.Map;

/**
 * Reads tool arguments, which arrive from the model as an untyped map.
 *
 * <p>The gateway has already checked them against the declared schema, so these
 * only have to cope with the shapes that schema allows — a number that arrived
 * as a string, an optional value that is absent.</p>
 */
final class ToolArgs {

    private ToolArgs() {
    }

    static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String valueOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    /** For a parameter the schema marks required; a blank one is a caller error. */
    static String require(Map<String, Object> params, String key) {
        String value = asString(params.get(key));
        if (isBlank(value)) {
            throw new IllegalArgumentException("缺少必要参数: " + key);
        }
        return value;
    }

    static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value != null ? Integer.parseInt(value.toString()) : defaultValue;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
