package io.github.wisesky0.jpqlcheck;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-4: Tests for type mismatch judgment logic.
 */
class TypeMismatchJudgmentTest {

    // UT-4-1: String type passed to numeric function -> should be ERROR
    @Test
    void stringTypeToNumericFunction_shouldBeMismatch() {
        Optional<FunctionEntry> entry = FunctionCatalog.lookup("abs");
        assertTrue(entry.isPresent());
        FunctionEntry fn = entry.get();
        assertEquals(TypeCategory.NUMERIC, fn.category());

        TypeCategory argCategory = TypeCategory.fromJavaType("java.lang.String");
        assertEquals(TypeCategory.STRING, argCategory);
        assertNotEquals(fn.category(), argCategory, "String passed to abs should be mismatch");
    }

    // UT-4-2: Numeric type passed to string function -> should be ERROR
    @Test
    void numericTypeToStringFunction_shouldBeMismatch() {
        Optional<FunctionEntry> entry = FunctionCatalog.lookup("length");
        assertTrue(entry.isPresent());
        FunctionEntry fn = entry.get();
        assertEquals(TypeCategory.STRING, fn.category());

        TypeCategory argCategory = TypeCategory.fromJavaType("java.lang.Long");
        assertEquals(TypeCategory.NUMERIC, argCategory);
        assertNotEquals(fn.category(), argCategory, "Numeric passed to length should be mismatch");
    }

    // UT-4-3: Integer passed to abs -> compatible
    @Test
    void integerTypeToAbsFunction_shouldBeCompatible() {
        Optional<FunctionEntry> entry = FunctionCatalog.lookup("abs");
        assertTrue(entry.isPresent());

        TypeCategory argCategory = TypeCategory.fromJavaType("java.lang.Integer");
        assertEquals(TypeCategory.NUMERIC, argCategory);
        assertEquals(entry.get().category(), argCategory);
    }

    // UT-4-4: String passed to lower -> compatible
    @Test
    void stringTypeToLowerFunction_shouldBeCompatible() {
        Optional<FunctionEntry> entry = FunctionCatalog.lookup("lower");
        assertTrue(entry.isPresent());

        TypeCategory argCategory = TypeCategory.fromJavaType("java.lang.String");
        assertEquals(TypeCategory.STRING, argCategory);
        assertEquals(entry.get().category(), argCategory);
    }

    // UT-4-5: UNRESOLVED type -> WARNING not ERROR
    @Test
    void unresolvedType_shouldBeWarningCategory() {
        TypeCategory category = TypeCategory.fromJavaType(TypeNormalizer.UNRESOLVED);
        assertEquals(TypeCategory.UNKNOWN, category,
            "UNRESOLVED type should map to UNKNOWN category (WARNING)");
    }

    // UT-4-6: LocalDateTime passed to extract -> compatible
    @Test
    void localDateTimeToExtract_secondArgShouldBeCompatible() {
        Optional<FunctionEntry> entry = FunctionCatalog.lookup("extract");
        assertTrue(entry.isPresent());

        TypeCategory argCategory = TypeCategory.fromJavaType("java.time.LocalDateTime");
        assertEquals(TypeCategory.DATETIME, argCategory);
        // extract's second arg is DATETIME
        assertEquals(TypeCategory.DATETIME, entry.get().argCategories().get(1));
        assertEquals(argCategory, entry.get().argCategories().get(1));
    }

    // UT-4-7: String passed to extract (temporal position) -> mismatch
    @Test
    void stringToExtractTemporalArg_shouldBeMismatch() {
        Optional<FunctionEntry> entry = FunctionCatalog.lookup("extract");
        assertTrue(entry.isPresent());

        TypeCategory argCategory = TypeCategory.fromJavaType("java.lang.String");
        TypeCategory expected = entry.get().argCategories().get(1);
        assertEquals(TypeCategory.DATETIME, expected);
        assertNotEquals(expected, argCategory);
    }

    // UT-4-8: concat with all strings -> compatible
    @Test
    void concatAllStrings_shouldBeCompatible() {
        Optional<FunctionEntry> entry = FunctionCatalog.lookup("concat");
        assertTrue(entry.isPresent());
        assertEquals(TypeCategory.STRING, entry.get().category());
        assertEquals(2, entry.get().minArgs());
        assertEquals(-1, entry.get().maxArgs(), "concat should accept unbounded args");
    }

    // UT-4-9: mod with BigDecimal -> WARNING
    @Test
    void modWithBigDecimal_isNumericCategory() {
        Optional<FunctionEntry> entry = FunctionCatalog.lookup("mod");
        assertTrue(entry.isPresent());

        TypeCategory argCategory = TypeCategory.fromJavaType("java.math.BigDecimal");
        assertEquals(TypeCategory.NUMERIC, argCategory,
            "BigDecimal is NUMERIC, mod should warn not error");
    }

    // UT-4-10: TypeCategory.fromJavaType for primitive types
    @Test
    void primitiveTypes_mapToNumeric() {
        assertEquals(TypeCategory.NUMERIC, TypeCategory.fromJavaType("int"));
        assertEquals(TypeCategory.NUMERIC, TypeCategory.fromJavaType("long"));
        assertEquals(TypeCategory.NUMERIC, TypeCategory.fromJavaType("double"));
        assertEquals(TypeCategory.NUMERIC, TypeCategory.fromJavaType("float"));
    }
}
