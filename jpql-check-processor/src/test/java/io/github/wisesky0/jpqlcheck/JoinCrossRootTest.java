package io.github.wisesky0.jpqlcheck;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRD2 D-10: JOIN ON 절 교차 루트 참조 감지 수용 기준 테스트 (TC2-01 ~ TC2-07).
 *
 * JPAQueryFactory 없이 QueryDSL StringPath + MockQuery 스텁으로 체인을 재현한다.
 * 분석기는 메서드 이름(from/innerJoin/on) 기반으로 동작하므로 스텁으로 검증 가능하다.
 */
class JoinCrossRootTest {

    /** QueryDSL 체인 메서드를 모사하는 테스트 전용 스텁 */
    private static final String MOCK_QUERY_SOURCE = """
        package test;
        public class MockQuery {
            public static MockQuery q() { return new MockQuery(); }
            public MockQuery from(Object... args) { return this; }
            public MockQuery innerJoin(Object t) { return this; }
            public MockQuery leftJoin(Object t) { return this; }
            public MockQuery on(Object cond) { return this; }
            public MockQuery where(Object cond) { return this; }
        }
        """;

    private static JavaFileObject mockQuery() {
        return JavaFileObjects.forSourceString("test.MockQuery", MOCK_QUERY_SOURCE);
    }

    private static JavaFileObject fixture(String body) {
        return JavaFileObjects.forSourceString("test.Fixture", """
            package test;
            import com.querydsl.core.types.dsl.*;
            class Fixture {
                void run() {
                    StringPath a = Expressions.stringPath("a");
                    StringPath b = Expressions.stringPath("b");
                    StringPath c = Expressions.stringPath("c");
                    StringPath d = Expressions.stringPath("d");
            %s
                }
            }
            """.formatted(body.indent(8)));
    }

    private static Compilation compile(String body) {
        return javac().withProcessors(new JpqlFunctionTypeProcessor())
            .compile(mockQuery(), fixture(body));
    }

    private static void assertDetected(String body) {
        assertThat(compile(body)).hadErrorContaining("[D-10]");
    }

    private static void assertClean(String body) {
        Compilation c = compile(body);
        assertThat(c).succeeded();
        assertTrue(c.diagnostics().stream()
                .map(d -> d.getMessage(null))
                .noneMatch(m -> m.contains("[D-10]")),
            "D-10이 감지되지 않아야 합니다: " + c.diagnostics());
    }

    // TC2-01: from(a, b).innerJoin(c).on(c.eq(a)) — a는 첫 번째 FROM 루트(sokcetRoot 아님) → D-10
    @Test
    void tc2_01_crossRootReferenceInOn_detected() {
        assertDetected("""
            MockQuery.q().from(a, b).innerJoin(c).on(c.eq(a));
            """);
    }

    // TC2-02: from(a) 단일 루트 — 위반 불가 → 정상
    @Test
    void tc2_02_singleFromRoot_clean() {
        assertClean("""
            MockQuery.q().from(a).innerJoin(b).on(b.eq(a));
            """);
    }

    // TC2-03: from(a, b).innerJoin(c).on(c.eq(b)) — b = 마지막 FROM 루트(attachedRoot) → 정상
    @Test
    void tc2_03_referenceToAttachedRoot_clean() {
        assertClean("""
            MockQuery.q().from(a, b).innerJoin(c).on(c.eq(b));
            """);
    }

    // TC2-04: 형제 JOIN 참조 — 정상 (E-11)
    @Test
    void tc2_04_siblingJoinReference_clean() {
        assertClean("""
            MockQuery.q().from(a, b).innerJoin(c).on(c.eq(b))
                .innerJoin(d).on(d.eq(c));
            """);
    }

    // TC2-05: 교차 조건이 WHERE에만 있는 경우 — 정상 (E-12)
    @Test
    void tc2_05_crossConditionInWhereOnly_clean() {
        assertClean("""
            MockQuery.q().from(a, b).innerJoin(c).on(c.eq(b))
                .where(c.eq(a));
            """);
    }

    // TC2-06: ON에 교차 참조 + WHERE 혼재 — D-10 감지 (ON 절만)
    @Test
    void tc2_06_crossRootInOnWithWhere_detected() {
        assertDetected("""
            MockQuery.q().from(a, b).innerJoin(c).on(c.eq(a))
                .where(c.eq(b));
            """);
    }

    // TC2-07: 변수 경유 — 1차 구현 known limitation (미감지 허용)
    @Test
    void tc2_07_variableMediated_notDetectedInFirstImpl() {
        Compilation c = compile("""
            Object cond = c.eq(a);
            MockQuery.q().from(a, b).innerJoin(c).on(cond);
            """);
        // 변수 경유 패턴은 1차 구현 범위 외 — 감지되지 않아도 테스트 통과
        assertThat(c).succeeded();
    }

    // failOnError=false 일 때 D-10이 WARNING으로 출력되고 컴파일은 성공해야 함
    @Test
    void failOnErrorFalse_d10ReportsAsWarning() {
        Compilation c = javac()
            .withProcessors(new JpqlFunctionTypeProcessor())
            .withOptions("-Ajpql.check.failOnError=false")
            .compile(mockQuery(), fixture("""
                MockQuery.q().from(a, b).innerJoin(c).on(c.eq(a));
                """));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("[D-10]");
    }
}
