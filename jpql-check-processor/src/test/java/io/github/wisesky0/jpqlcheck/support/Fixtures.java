package io.github.wisesky0.jpqlcheck.support;

import io.github.wisesky0.jpqlcheck.Finding;
import io.github.wisesky0.jpqlcheck.TypeNormalizer;

import java.util.List;

/**
 * Test fixtures for JPQL check tests.
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

    public static Finding error1Finding(String functionName, String offendingExpr,
                                        String inferredType, String expectedCategory) {
        return new Finding("D-01", "ERROR", "TestFile.java", 10L, 5L,
            functionName.toUpperCase() + "({0})", functionName, offendingExpr,
            inferredType, expectedCategory, -1L,
            "등록 함수 " + functionName + "의 인자 타입 불일치 (PRD 오류 1)",
            "cast 적용 — \"" + functionName.toUpperCase() + "(cast({0} as long))\" (PRD E-01)");
    }

    public static Finding error2Finding(String functionName, String offendingExpr, long relatedLine) {
        return new Finding(relatedLine >= 0 ? "D-08" : "D-03", "ERROR", "TestFile.java", 20L, 5L,
            functionName.toUpperCase() + "({0}, {1})", functionName, offendingExpr,
            "java.lang.String", "java.lang.Object(미등록 함수 반환 추론)", relatedLine,
            "미등록 함수 " + functionName + "의 반환이 Object로 추론되어 Path와 비교 시 예외 (PRD 오류 2)",
            "function('" + functionName.toUpperCase() + "', ...) 구문으로 우회 (PRD E-02)");
    }

    public static Finding warningFinding(String functionName, String offendingExpr) {
        return new Finding("D-01", "WARNING", "TestFile.java", 10L, 5L,
            functionName.toUpperCase() + "({0})", functionName, offendingExpr,
            TypeNormalizer.UNRESOLVED, "NUMERIC", -1L,
            "UNRESOLVED - 타입을 정적으로 결정할 수 없습니다", null);
    }

    public static List<Finding> sampleFindings() {
        return List.of(
            error1Finding("abs", "product.name", "java.lang.String", "NUMERIC"),
            error2Finding("date_format", "order.stringDttm", -1L),
            warningFinding("sqrt", "item.score")
        );
    }
}
