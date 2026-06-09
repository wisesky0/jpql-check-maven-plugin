package io.github.wisesky0.jpqlcheck;

import io.github.wisesky0.jpqlcheck.support.Fixtures;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeNormalizerTest {

    @Test
    void stringPath_normalizesToJavaLangString() {
        assertEquals("java.lang.String",
            TypeNormalizer.normalizeFromSignature(Fixtures.STRING_PATH_SIG));
    }

    @Test
    void booleanPath_normalizesToJavaLangBoolean() {
        assertEquals("java.lang.Boolean",
            TypeNormalizer.normalizeFromSignature(Fixtures.BOOLEAN_PATH_SIG));
    }

    @Test
    void numberPathInteger_normalizesToInteger() {
        assertEquals("java.lang.Integer",
            TypeNormalizer.normalizeFromSignature(Fixtures.NUMBER_PATH_INTEGER_SIG));
    }

    @Test
    void numberPathLong_normalizesToLong() {
        assertEquals("java.lang.Long",
            TypeNormalizer.normalizeFromSignature(Fixtures.NUMBER_PATH_LONG_SIG));
    }

    @Test
    void numberPathBigDecimal_normalizesToBigDecimal() {
        assertEquals("java.math.BigDecimal",
            TypeNormalizer.normalizeFromSignature(Fixtures.NUMBER_PATH_BIGDECIMAL_SIG));
    }

    @Test
    void dateTimePath_normalizesToLocalDateTime() {
        assertEquals("java.time.LocalDateTime",
            TypeNormalizer.normalizeFromSignature(Fixtures.DATETIME_PATH_LOCAL_DATETIME_SIG));
    }

    @Test
    void datePath_normalizesToLocalDate() {
        assertEquals("java.time.LocalDate",
            TypeNormalizer.normalizeFromSignature(Fixtures.DATE_PATH_LOCAL_DATE_SIG));
    }

    @Test
    void numberPathWildcard_isUnresolved() {
        String result = TypeNormalizer.normalizeFromSignature(Fixtures.NUMBER_PATH_WILDCARD_SIG);
        assertTrue(TypeNormalizer.isUnresolved(result),
            "Wildcard NumberPath should be UNRESOLVED, was: " + result);
    }

    @Test
    void simplePath_isUnresolved() {
        String result = TypeNormalizer.normalizeFromSignature(Fixtures.SIMPLE_PATH_SIG);
        assertTrue(TypeNormalizer.isUnresolved(result),
            "SimplePath should be UNRESOLVED, was: " + result);
    }

    @Test
    void expressionWildcard_isUnresolved() {
        String result = TypeNormalizer.normalizeFromSignature(Fixtures.EXPRESSION_WILDCARD_SIG);
        assertTrue(TypeNormalizer.isUnresolved(result),
            "Expression<?> should be UNRESOLVED, was: " + result);
    }

    @Test
    void nullInput_isUnresolved() {
        String result = TypeNormalizer.normalizeFromSignature(null);
        assertTrue(TypeNormalizer.isUnresolved(result));
    }

    @Test
    void isUnresolved_trueForUnresolvedConstant() {
        assertTrue(TypeNormalizer.isUnresolved(TypeNormalizer.UNRESOLVED));
    }

    @Test
    void isUnresolved_falseForJavaType() {
        assertFalse(TypeNormalizer.isUnresolved("java.lang.String"));
    }
}
