package io.github.wisesky0.jpqlcheck;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TypeNormalizer {
    public static final String UNRESOLVED = "UNRESOLVED";

    private static final Map<String, String> SIMPLE_MAPPINGS = Map.of(
        "com.querydsl.core.types.dsl.StringPath", "java.lang.String",
        "com.querydsl.core.types.dsl.BooleanPath", "java.lang.Boolean",
        "com.querydsl.core.types.dsl.SimplePath", UNRESOLVED
    );
    private static final Pattern GENERIC_PATTERN =
        Pattern.compile("com\\.querydsl\\.core\\.types\\.dsl\\.(Number|DateTime|Date|Time)Path<(.+)>");

    private TypeNormalizer() {}

    public static String normalizeFromSignature(String typeSig) {
        if (typeSig == null) return UNRESOLVED;
        String trimmed = typeSig.trim();
        // Check simple mappings
        if (SIMPLE_MAPPINGS.containsKey(trimmed)) return SIMPLE_MAPPINGS.get(trimmed);
        // Check generic patterns
        Matcher m = GENERIC_PATTERN.matcher(trimmed);
        if (m.matches()) {
            String param = m.group(2).trim();
            if (param.contains("?") || param.equals("")) return UNRESOLVED;
            return param;
        }
        // Expression<?> or raw Expression
        if (trimmed.contains("Expression<?>") || trimmed.matches(".*Expression$")) return UNRESOLVED;
        if (trimmed.contains("?")) return UNRESOLVED;
        return UNRESOLVED;
    }

    public static boolean isUnresolved(String normalized) {
        return UNRESOLVED.equals(normalized);
    }
}
