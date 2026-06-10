package io.github.wisesky0.jpqlcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;

/**
 * Hibernate 6.6.49.Final 등록 함수 카탈로그 (PRD R-03, I-02).
 * 데이터는 리소스 파일 jpql-functions.json 에서 로드하며,
 * 리소스를 읽을 수 없는 환경에서는 내장 기본값으로 폴백한다.
 * 카탈로그에 없는 함수명은 미등록 함수로 취급한다 (PRD R-04).
 */
public final class FunctionCatalog {
    private static final Map<String, FunctionEntry> FUNCTIONS = load();

    private FunctionCatalog() {}

    public static Optional<FunctionEntry> lookup(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(FUNCTIONS.get(name.toLowerCase(Locale.ROOT)));
    }

    public static boolean isRegistered(String name) {
        return lookup(name).isPresent();
    }

    private static Map<String, FunctionEntry> load() {
        try (InputStream in = FunctionCatalog.class.getResourceAsStream("/jpql-functions.json")) {
            if (in != null) {
                Map<String, FunctionEntry> loaded = parse(in);
                if (!loaded.isEmpty()) return loaded;
            }
        } catch (Exception ignored) {
            // 폴백으로 진행
        }
        return builtIn();
    }

    private static Map<String, FunctionEntry> parse(InputStream in) throws Exception {
        Map<String, FunctionEntry> map = new HashMap<>();
        JsonNode root = new ObjectMapper().readTree(in);
        for (JsonNode fn : root.path("functions")) {
            String name = fn.path("name").asText(null);
            if (name == null || name.isBlank()) continue;
            TypeCategory category = TypeCategory.valueOf(fn.path("category").asText("GENERAL"));
            int minArgs = fn.path("minArgs").asInt(0);
            int maxArgs = fn.path("maxArgs").asInt(-1);
            List<TypeCategory> argCategories = null;
            JsonNode argsNode = fn.path("argCategories");
            if (argsNode.isArray()) {
                argCategories = new ArrayList<>();
                for (JsonNode c : argsNode) argCategories.add(TypeCategory.valueOf(c.asText()));
            }
            String source = fn.path("source").asText("JPA_STANDARD");
            String minJpaVersion = fn.path("minJpaVersion").asText("1.0");
            map.put(name.toLowerCase(Locale.ROOT),
                new FunctionEntry(name.toLowerCase(Locale.ROOT), category, minArgs, maxArgs,
                    argCategories, source, minJpaVersion));
        }
        return map;
    }

    private static Map<String, FunctionEntry> builtIn() {
        Map<String, FunctionEntry> m = new HashMap<>();
        for (String fn : List.of("abs", "sign", "floor", "ceiling", "sqrt", "exp", "ln")) {
            m.put(fn, new FunctionEntry(fn, TypeCategory.NUMERIC, 1, 1,
                List.of(TypeCategory.NUMERIC), "JPA_STANDARD", "2.0"));
        }
        m.put("power", new FunctionEntry("power", TypeCategory.NUMERIC, 2, 2,
            List.of(TypeCategory.NUMERIC, TypeCategory.NUMERIC), "JPA_STANDARD", "2.0"));
        m.put("mod", new FunctionEntry("mod", TypeCategory.NUMERIC, 2, 2,
            List.of(TypeCategory.NUMERIC, TypeCategory.NUMERIC), "JPA_STANDARD", "1.0"));
        m.put("round", new FunctionEntry("round", TypeCategory.NUMERIC, 1, 2,
            List.of(TypeCategory.NUMERIC, TypeCategory.NUMERIC), "JPA_STANDARD", "3.2"));
        m.put("length", new FunctionEntry("length", TypeCategory.STRING, 1, 1,
            List.of(TypeCategory.STRING), "JPA_STANDARD", "1.0"));
        for (String fn : List.of("lower", "upper", "trim")) {
            m.put(fn, new FunctionEntry(fn, TypeCategory.STRING, 1, 1,
                List.of(TypeCategory.STRING), "JPA_STANDARD", "1.0"));
        }
        m.put("substring", new FunctionEntry("substring", TypeCategory.STRING, 2, 3,
            List.of(TypeCategory.STRING, TypeCategory.NUMERIC, TypeCategory.NUMERIC), "JPA_STANDARD", "1.0"));
        m.put("locate", new FunctionEntry("locate", TypeCategory.STRING, 2, 3,
            List.of(TypeCategory.STRING, TypeCategory.STRING, TypeCategory.NUMERIC), "JPA_STANDARD", "1.0"));
        m.put("concat", new FunctionEntry("concat", TypeCategory.STRING, 2, -1,
            null, "JPA_STANDARD", "1.0"));
        m.put("extract", new FunctionEntry("extract", TypeCategory.DATETIME, 2, 2,
            List.of(TypeCategory.GENERAL, TypeCategory.DATETIME), "JPA_STANDARD", "2.0"));
        m.put("coalesce", new FunctionEntry("coalesce", TypeCategory.GENERAL, 2, -1,
            null, "JPA_STANDARD", "1.0"));
        m.put("nullif", new FunctionEntry("nullif", TypeCategory.GENERAL, 2, 2,
            null, "JPA_STANDARD", "1.0"));
        m.put("cast", new FunctionEntry("cast", TypeCategory.GENERAL, 2, 2,
            null, "JPA_STANDARD", "1.0"));
        return m;
    }
}
