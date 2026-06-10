package io.github.wisesky0.jpqlcheck;

import io.github.wisesky0.jpqlcheck.support.Fixtures;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding 모델의 진단 정보 요구사항 검증 (PRD I-05, I-06).
 */
class DiagnosticContentTest {

    @Test
    void error1Finding_hasRuleIdAndSeverity() {
        Finding f = Fixtures.error1Finding("abs", "product.name", "java.lang.String", "NUMERIC");
        assertEquals("D-01", f.ruleId());
        assertEquals("ERROR", f.severity());
    }

    @Test
    void error2Finding_directComparison_hasRuleD03() {
        Finding f = Fixtures.error2Finding("date_format", "order.stringDttm", -1L);
        assertEquals("D-03", f.ruleId());
        assertEquals(-1L, f.relatedLine());
    }

    @Test
    void error2Finding_viaVariable_hasRuleD08AndRelatedLine() {
        Finding f = Fixtures.error2Finding("date_format", "order.stringDttm", 15L);
        assertEquals("D-08", f.ruleId());
        assertEquals(15L, f.relatedLine(), "I-06: 템플릿 대입 라인이 포함되어야 함");
    }

    @Test
    void finding_containsTemplateString() {
        Finding f = Fixtures.error1Finding("abs", "product.name", "java.lang.String", "NUMERIC");
        assertNotNull(f.templateString(), "I-05: 템플릿 문자열 원문 포함");
        assertTrue(f.templateString().contains("ABS"));
    }

    @Test
    void finding_containsOffendingExpressionAndTypes() {
        Finding f = Fixtures.error1Finding("abs", "product.name", "java.lang.String", "NUMERIC");
        assertEquals("product.name", f.offendingExpression());
        assertEquals("java.lang.String", f.inferredType());
        assertEquals("NUMERIC", f.expectedType());
    }

    @Test
    void finding_containsRecommendation() {
        Finding e1 = Fixtures.error1Finding("abs", "product.name", "java.lang.String", "NUMERIC");
        assertTrue(e1.recommendation().contains("cast"), "I-05: E-01 권장 우회 포함");

        Finding e2 = Fixtures.error2Finding("date_format", "order.stringDttm", -1L);
        assertTrue(e2.recommendation().contains("function("), "I-05: E-02 권장 우회 포함");
    }

    @Test
    void finding_containsSourceFileAndPosition() {
        Finding f = Fixtures.error1Finding("abs", "product.name", "java.lang.String", "NUMERIC");
        assertNotNull(f.sourceFile());
        assertTrue(f.line() > 0);
        assertTrue(f.column() >= 0);
    }

    @Test
    void warningFinding_hasSeverityWarning() {
        Finding f = Fixtures.warningFinding("sqrt", "item.score");
        assertEquals("WARNING", f.severity());
        assertTrue(f.reason().contains("UNRESOLVED") || f.reason().contains("정적"));
    }

    @Test
    void sampleFindings_hasExpectedSeverities() {
        var findings = Fixtures.sampleFindings();
        long errors = findings.stream().filter(f -> "ERROR".equals(f.severity())).count();
        long warnings = findings.stream().filter(f -> "WARNING".equals(f.severity())).count();
        assertEquals(2, errors);
        assertEquals(1, warnings);
    }
}
