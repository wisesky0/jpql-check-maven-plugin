package io.github.wisesky0.jpqlcheck;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRD 7장 수용 기준 테스트 케이스 매트릭스 (TC-01 ~ TC-18).
 */
class AcceptanceCriteriaTest {

    private static JavaFileObject fixture(String body) {
        return JavaFileObjects.forSourceString("test.Fixture", """
            package test;

            import com.querydsl.core.types.ConstantImpl;
            import com.querydsl.core.types.dsl.*;

            class Fixture {
                void run() {
                    StringPath col = Expressions.stringPath("col");
                    StringPath dttm = Expressions.stringPath("dttm");
                    NumberPath<Long> num = Expressions.numberPath(Long.class, "num");
            %s
                }
            }
            """.formatted(body.indent(8)));
    }

    private static Compilation compile(String body) {
        return javac().withProcessors(new JpqlFunctionTypeProcessor()).compile(fixture(body));
    }

    private static void assertDetected(String body, String ruleId) {
        Compilation compilation = compile(body);
        assertThat(compilation).hadErrorContaining("[" + ruleId + "]");
    }

    private static void assertClean(String body) {
        Compilation compilation = compile(body);
        assertThat(compilation).succeeded();
        assertTrue(compilation.diagnostics().stream()
                .map(d -> d.getMessage(null))
                .noneMatch(m -> m.contains("[D-") || m.contains("[I-03]")),
            "감지 규칙 진단이 없어야 합니다: " + compilation.diagnostics());
    }

    // --- 오류 1 (D-01, D-02, E-01) ---

    @Test
    void tc01_registeredFunctionWithIncompatiblePath_detected() {
        assertDetected("""
            NumberExpression<Long> e = Expressions.numberTemplate(Long.class, "ABS({0})", col);
            """, "D-01");
    }

    @Test
    void tc02_registeredFunctionWithCompatiblePath_clean() {
        assertClean("""
            NumberExpression<Long> e = Expressions.numberTemplate(Long.class, "ABS({0})", num);
            """);
    }

    @Test
    void tc03_registeredFunctionWithConstantBinding_clean() {
        assertClean("""
            NumberExpression<Long> e = Expressions.numberTemplate(Long.class, "ABS({0})", 5);
            """);
    }

    @Test
    void tc04_castAppliedArgument_excluded() {
        assertClean("""
            NumberExpression<Long> e = Expressions.numberTemplate(Long.class, "ABS(cast({0} as long))", col);
            """);
    }

    // --- 오류 2 직접 비교 (D-03 ~ D-07, E-02) ---

    @Test
    void tc05_unregisteredFunctionComparedWithPath_detected() {
        assertDetected("""
            BooleanExpression p = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d").goe(dttm);
            """, "D-03");
    }

    @Test
    void tc06_templateOnRightSideOfComparison_detected() {
        assertDetected("""
            BooleanExpression p = dttm.goe(Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d"));
            """, "D-03");
    }

    @Test
    void tc07_literalFormatArgument_detected() {
        assertDetected("""
            BooleanExpression p = Expressions.stringTemplate("DATE_FORMAT({0}, '%Y%m%d')", col).goe(dttm);
            """, "D-03");
    }

    @Test
    void tc08_comparedWithStringValue_clean() {
        assertClean("""
            BooleanExpression p = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d").goe("20210901");
            """);
    }

    @Test
    void tc09_comparedWithConstantImpl_clean() {
        assertClean("""
            BooleanExpression p = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d")
                .goe(ConstantImpl.create("20201212"));
            """);
    }

    @Test
    void tc10_comparedWithStringPath_detected() {
        assertDetected("""
            BooleanExpression p = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d")
                .goe(Expressions.stringPath("x"));
            """, "D-03");
    }

    @Test
    void tc11_functionSyntax_excluded() {
        assertClean("""
            BooleanExpression p = Expressions.stringTemplate("function('DATE_FORMAT', {0}, {1})", col, "%Y%m%d").goe(dttm);
            """);
    }

    @Test
    void tc12_unregisteredTemplateWithoutComparison_clean() {
        assertClean("""
            StringExpression v = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d");
            """);
    }

    @Test
    void tc13_nonConstantTemplateString_warned() {
        Compilation compilation = compile("""
            String t = java.util.UUID.randomUUID().toString();
            StringExpression v = Expressions.stringTemplate(t, col);
            """);
        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("[I-03]");
    }

    @Test
    void tc14_registeredFunctionCompatibleStringPath_clean() {
        assertClean("""
            StringExpression e = Expressions.stringTemplate("LOWER({0})", col);
            """);
    }

    // --- 오류 2 변수 경유 (D-08) ---

    @Test
    void tc15_riskyVariableComparedWithPath_detected() {
        assertDetected("""
            StringExpression v = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d");
            BooleanExpression p = v.goe(dttm);
            """, "D-08");
    }

    @Test
    void tc16_riskyVariableComparedWithStringValue_clean() {
        assertClean("""
            StringExpression v = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d");
            BooleanExpression p = v.goe("20210901");
            """);
    }

    @Test
    void tc17_riskyVariableOnRightSide_detected() {
        assertDetected("""
            StringExpression v = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d");
            BooleanExpression p = dttm.loe(v);
            """, "D-08");
    }

    @Test
    void tc18_riskyVariableWithGtComparison_detected() {
        assertDetected("""
            StringExpression v = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d");
            BooleanExpression p = v.gt(dttm);
            """, "D-08");
    }

    // --- 부가 검증: D-08 Finding의 관련 라인 메시지 (I-06) ---

    @Test
    void d08_messageIncludesAssignmentLine() {
        Compilation compilation = compile("""
            StringExpression v = Expressions.stringTemplate("DATE_FORMAT({0}, {1})", col, "%Y%m%d");
            BooleanExpression p = v.goe(dttm);
            """);
        assertThat(compilation).hadErrorContaining("템플릿 대입 지점");
    }
}
