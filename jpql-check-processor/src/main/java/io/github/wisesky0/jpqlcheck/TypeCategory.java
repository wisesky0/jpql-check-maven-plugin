package io.github.wisesky0.jpqlcheck;

public enum TypeCategory {
    STRING, NUMERIC, DATETIME, GENERAL, AGGREGATE, UNKNOWN;

    public static TypeCategory fromJavaType(String javaType) {
        if (javaType == null || TypeNormalizer.UNRESOLVED.equals(javaType)) return UNKNOWN;
        return switch (javaType) {
            case "java.lang.String", "java.lang.CharSequence" -> STRING;
            case "java.lang.Integer", "java.lang.Long", "java.lang.Double",
                 "java.lang.Float", "java.lang.Short", "java.lang.Byte",
                 "java.math.BigDecimal", "java.math.BigInteger",
                 "int", "long", "double", "float", "short", "byte" -> NUMERIC;
            case "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
                 "java.time.LocalTime", "java.time.ZonedDateTime", "java.util.Date",
                 "java.util.Calendar" -> DATETIME;
            default -> UNKNOWN;
        };
    }
}
