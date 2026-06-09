package io.github.wisesky0.jpqlcheck;

import java.util.*;

public final class FunctionCatalog {
    private static final Map<String, FunctionEntry> BUILT_IN = new HashMap<>();

    static {
        // NUMERIC - single arg, Number
        for (String fn : List.of("abs", "sign", "floor", "ceiling")) {
            BUILT_IN.put(fn, new FunctionEntry(fn, TypeCategory.NUMERIC, 1, 1,
                List.of(TypeCategory.NUMERIC), "JPA_STANDARD", "2.0"));
        }
        for (String fn : List.of("sqrt", "exp", "ln")) {
            BUILT_IN.put(fn, new FunctionEntry(fn, TypeCategory.NUMERIC, 1, 1,
                List.of(TypeCategory.NUMERIC), "JPA_STANDARD", "2.0"));
        }
        BUILT_IN.put("power", new FunctionEntry("power", TypeCategory.NUMERIC, 2, 2,
            List.of(TypeCategory.NUMERIC, TypeCategory.NUMERIC), "JPA_STANDARD", "2.0"));
        BUILT_IN.put("mod", new FunctionEntry("mod", TypeCategory.NUMERIC, 2, 2,
            List.of(TypeCategory.NUMERIC, TypeCategory.NUMERIC), "JPA_STANDARD", "1.0"));
        BUILT_IN.put("round", new FunctionEntry("round", TypeCategory.NUMERIC, 1, 2,
            List.of(TypeCategory.NUMERIC, TypeCategory.NUMERIC), "JPA_STANDARD", "3.2"));
        // STRING
        BUILT_IN.put("length", new FunctionEntry("length", TypeCategory.STRING, 1, 1,
            List.of(TypeCategory.STRING), "JPA_STANDARD", "1.0"));
        for (String fn : List.of("lower", "upper", "trim")) {
            BUILT_IN.put(fn, new FunctionEntry(fn, TypeCategory.STRING, 1, 1,
                List.of(TypeCategory.STRING), "JPA_STANDARD", "1.0"));
        }
        BUILT_IN.put("substring", new FunctionEntry("substring", TypeCategory.STRING, 2, 3,
            List.of(TypeCategory.STRING, TypeCategory.NUMERIC, TypeCategory.NUMERIC), "JPA_STANDARD", "1.0"));
        BUILT_IN.put("locate", new FunctionEntry("locate", TypeCategory.STRING, 2, 3,
            List.of(TypeCategory.STRING, TypeCategory.STRING, TypeCategory.NUMERIC), "JPA_STANDARD", "1.0"));
        BUILT_IN.put("concat", new FunctionEntry("concat", TypeCategory.STRING, 2, -1,
            null, "JPA_STANDARD", "1.0")); // all STRING
        // DATETIME
        BUILT_IN.put("extract", new FunctionEntry("extract", TypeCategory.DATETIME, 2, 2,
            List.of(TypeCategory.GENERAL, TypeCategory.DATETIME), "JPA_STANDARD", "2.0"));
        // GENERAL
        BUILT_IN.put("coalesce", new FunctionEntry("coalesce", TypeCategory.GENERAL, 2, -1,
            null, "JPA_STANDARD", "1.0")); // same category
        BUILT_IN.put("nullif", new FunctionEntry("nullif", TypeCategory.GENERAL, 2, 2,
            null, "JPA_STANDARD", "1.0"));
        BUILT_IN.put("cast", new FunctionEntry("cast", TypeCategory.GENERAL, 2, 2,
            null, "JPA_STANDARD", "1.0"));
    }

    private FunctionCatalog() {}

    public static Optional<FunctionEntry> lookup(String name) {
        return Optional.ofNullable(BUILT_IN.get(name.toLowerCase()));
    }
}
