package io.github.wisesky0.jacksoncheck;

import io.github.wisesky0.jacksoncheck.model.ClassInfo;
import io.github.wisesky0.jacksoncheck.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static io.github.wisesky0.jacksoncheck.model.RiskLevel.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskJudgeTest {

    private static ClassInfo info(Consumer<ClassInfo> init) {
        ClassInfo i = new ClassInfo();
        i.fqcn = "test.Dto";
        init.accept(i);
        return i;
    }

    // ── Safe patterns ──────────────────────────────────────────────────────

    @Test
    void noConstructors_implicitNoArg_safe() {
        assertEquals(SAFE, RiskJudge.judge(info(i -> {})));
    }

    @Test
    void explicitNoArg_s1_safe() {
        assertEquals(SAFE, RiskJudge.judge(info(i -> i.constructors.explicitParamCounts.add(0))));
    }

    @Test
    void noArgLombok_s1_safe() {
        assertEquals(SAFE, RiskJudge.judge(info(i ->
            i.constructors.lombokAnnotations.add("NoArgsConstructor"))));
    }

    @Test
    void noArgPlusSingleArg_noArgWins_safe() {
        assertEquals(SAFE, RiskJudge.judge(info(i -> {
            i.constructors.explicitParamCounts.add(0);
            i.constructors.explicitParamCounts.add(1);
        })));
    }

    @Test
    void jsonCreator_s2_safe() {
        assertEquals(SAFE, RiskJudge.judge(info(i -> i.constructors.hasJsonCreatorOnCtor = true)));
    }

    @Test
    void jacksonized_s2_safe() {
        assertEquals(SAFE, RiskJudge.judge(info(i ->
            i.constructors.lombokAnnotations.add("Jacksonized"))));
    }

    @Test
    void record_s3_safe() {
        assertEquals(SAFE, RiskJudge.judge(info(i -> i.isRecord = true)));
    }

    @Test
    void enumType_s4_excluded() {
        assertEquals(EXCLUDED, RiskJudge.judge(info(i -> i.isEnum = true)));
    }

    @Test
    void interfaceType_s4_excluded() {
        assertEquals(EXCLUDED, RiskJudge.judge(info(i -> i.isInterface = true)));
    }

    // ── Risky patterns ─────────────────────────────────────────────────────

    @Test
    void singleArgOnly_r2() {
        assertEquals(R2, RiskJudge.judge(info(i -> i.constructors.explicitParamCounts.add(1))));
    }

    @Test
    void singleArgPlusMultiArg_r1() {
        assertEquals(R1, RiskJudge.judge(info(i -> {
            i.constructors.explicitParamCounts.add(1);
            i.constructors.explicitParamCounts.add(3);
        })));
    }

    @Test
    void singleArgPlusAllArgsLombok_r1() {
        assertEquals(R1, RiskJudge.judge(info(i -> {
            i.constructors.explicitParamCounts.add(1);
            i.constructors.lombokAnnotations.add("AllArgsConstructor");
        })));
    }

    @Test
    void singleArgPlusBuilder_r1() {
        assertEquals(R1, RiskJudge.judge(info(i -> {
            i.constructors.explicitParamCounts.add(1);
            i.constructors.lombokAnnotations.add("Builder");
        })));
    }

    @Test
    void multiArgOnly_r3() {
        assertEquals(R3, RiskJudge.judge(info(i -> {
            i.constructors.explicitParamCounts.add(2);
            i.constructors.explicitParamCounts.add(3);
        })));
    }

    @Test
    void allArgsLombokOnly_r4() {
        assertEquals(R4, RiskJudge.judge(info(i ->
            i.constructors.lombokAnnotations.add("AllArgsConstructor"))));
    }

    @Test
    void builderLombokOnly_r4() {
        assertEquals(R4, RiskJudge.judge(info(i ->
            i.constructors.lombokAnnotations.add("Builder"))));
    }

    @Test
    void valueLombok_r4() {
        assertEquals(R4, RiskJudge.judge(info(i ->
            i.constructors.lombokAnnotations.add("Value"))));
    }

    @Test
    void riskLevel_atLeastAsRiskyAs_ordering() {
        assertTrue(R1.atLeastAsRiskyAs(R1));
        assertTrue(R1.atLeastAsRiskyAs(R2));
        assertTrue(R1.atLeastAsRiskyAs(R3));
        assertTrue(R1.atLeastAsRiskyAs(R4));
        assertFalse(R2.atLeastAsRiskyAs(R1));
        assertFalse(R4.atLeastAsRiskyAs(R3));
    }

    @Test
    void riskLevel_isRisky() {
        assertTrue(R1.isRisky());
        assertTrue(R2.isRisky());
        assertTrue(R3.isRisky());
        assertTrue(R4.isRisky());
        assertFalse(SAFE.isRisky());
        assertFalse(EXCLUDED.isRisky());
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
