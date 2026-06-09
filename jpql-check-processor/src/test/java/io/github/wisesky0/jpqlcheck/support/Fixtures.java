package io.github.wisesky0.jpqlcheck.support;

import io.github.wisesky0.jpqlcheck.Finding;
import io.github.wisesky0.jpqlcheck.TypeCategory;
import io.github.wisesky0.jpqlcheck.TypeNormalizer;

import java.util.List;

/**
 * Test fixtures for JPQL function type mismatch tests.
 */
public final class Fixtures {

    private Fixtures() {}

    // --- TypeNormalizer fixtures ---

    public static final String STRING_PATH_SIG = "com.querydsl.core.types.dsl.StringPath";
    public static final String BOOLEAN_PATH_SIG = "com.querydsl.core.types.dsl.BooleanPath";
    public static final String NUMBER_PATH_INTEGER_SIG = "com.querydsl.core.types.dsl.NumberPath<java.lang.Integer>";
    public static final String NUMBER_PATH_LONG_SIG = "com.querydsl.core.types.dsl.NumberPath<java.lang.Long>";
    public static final String NUMBER_PATH_BIGDECIMAL_SIG = "com.querydsl.core.types.dsl.NumberPath<java.math.BigDecimal>";
    public static final String DATETIME_PATH_LOCAL_DATETIME_SIG = "com.querydsl.core.types.dsl.DateTimePath<java.time.LocalDateTime>";
    public static final String DATE_PATH_LOCAL_DATE_SIG = "com.querydsl.core.types.dsl.DatePath<java.time.LocalDate>";
    public static final String NUMBER_PATH_WILDCARD_SIG = "com.querydsl.core.types.dsl.NumberPath<?>";
    public static final String SIMPLE_PATH_SIG = "com.querydsl.core.types.dsl.SimplePath";
    public static final String EXPRESSION_WILDCARD_SIG = "Expression<?>";

    // --- Finding fixtures ---

    public static Finding errorFinding(String functionName, String entity, String col,
                                       String actualType, String expectedCategory) {
        return new Finding("TestFile.java", 10L, 5L, functionName,
            entity, col, actualType, expectedCategory, "ERROR",
            "ArgumentsValidator: " + functionName + " requires " + expectedCategory);
    }

    public static Finding warningFinding(String functionName, String entity, String col,
                                         String actualType, String expectedCategory) {
        return new Finding("TestFile.java", 10L, 5L, functionName,
            entity, col, actualType, expectedCategory, "WARNING",
            "UNRESOLVED - 타입을 정적으로 결정할 수 없습니다");
    }

    public static List<Finding> sampleFindings() {
        return List.of(
            errorFinding("abs", "Product", "name", "java.lang.String", "NUMERIC"),
            errorFinding("length", "Order", "amount", "java.lang.Long", "STRING"),
            warningFinding("sqrt", "Item", "score", TypeNormalizer.UNRESOLVED, "NUMERIC")
        );
    }
}
