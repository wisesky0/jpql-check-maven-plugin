package io.github.wisesky0.jpqlcheck;

import io.github.wisesky0.jpqlcheck.support.Fixtures;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for diagnostic content (Finding record fields).
 */
class DiagnosticContentTest {

    @Test
    void errorFinding_hasSeverityError() {
        Finding f = Fixtures.errorFinding("abs", "Product", "name", "java.lang.String", "NUMERIC");
        assertEquals("ERROR", f.severity());
    }

    @Test
    void warningFinding_hasSeverityWarning() {
        Finding f = Fixtures.warningFinding("sqrt", "Item", "score", TypeNormalizer.UNRESOLVED, "NUMERIC");
        assertEquals("WARNING", f.severity());
    }

    @Test
    void finding_containsFunctionName() {
        Finding f = Fixtures.errorFinding("length", "Order", "amount", "java.lang.Long", "STRING");
        assertEquals("length", f.functionName());
    }

    @Test
    void finding_containsEntityAndColumn() {
        Finding f = Fixtures.errorFinding("abs", "Product", "name", "java.lang.String", "NUMERIC");
        assertEquals("Product", f.entity());
        assertEquals("name", f.columnName());
    }

    @Test
    void finding_containsActualAndExpectedType() {
        Finding f = Fixtures.errorFinding("abs", "Product", "name", "java.lang.String", "NUMERIC");
        assertEquals("java.lang.String", f.actualType());
        assertEquals("NUMERIC", f.expectedType());
    }

    @Test
    void finding_containsSourceFileAndPosition() {
        Finding f = Fixtures.errorFinding("abs", "Product", "name", "java.lang.String", "NUMERIC");
        assertNotNull(f.sourceFile());
        assertTrue(f.line() > 0);
        assertTrue(f.column() >= 0);
    }

    @Test
    void finding_reasonIsNotEmpty() {
        Finding f = Fixtures.errorFinding("abs", "Product", "name", "java.lang.String", "NUMERIC");
        assertNotNull(f.reason());
        assertFalse(f.reason().isBlank());
    }

    @Test
    void unresolvedFinding_reasonMentionsUnresolved() {
        Finding f = Fixtures.warningFinding("sqrt", "Item", "score", TypeNormalizer.UNRESOLVED, "NUMERIC");
        assertTrue(f.reason().contains("UNRESOLVED") || f.reason().contains("정적"),
            "Reason should mention UNRESOLVED: " + f.reason());
    }

    @Test
    void sampleFindings_containsThreeItems() {
        assertEquals(3, Fixtures.sampleFindings().size());
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
