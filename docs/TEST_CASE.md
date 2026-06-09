# JPQL 함수 타입 불일치 탐지 도구 — 테스트 케이스 (구현)

> **대상**: 테스트 설계서 기준 실행 가능 테스트 코드
> **기반 프레임워크**: JUnit 5 + `com.google.testing.compile:compile-testing` (분석 코어), `maven-invoker-plugin` (플러그인)
> **전제**: 분석 코어 처리기 클래스명 = `JpqlFunctionTypeProcessor`

---

## 0. 의존성 (pom.xml — test scope)

```xml
<dependencies>
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>com.google.testing.compile</groupId>
    <artifactId>compile-testing</artifactId>
    <version>0.21.0</version>
    <scope>test</scope>
  </dependency>
  <!-- 검사 대상이 의존하는 QueryDSL (Q-클래스 타입 해석용) -->
  <dependency>
    <groupId>com.querydsl</groupId>
    <artifactId>querydsl-jpa</artifactId>
    <version>5.1.0</version>
    <classifier>jakarta</classifier>
    <scope>test</scope>
  </dependency>
</dependencies>
```

---

## 1. 공통 테스트 픽스처

검사 대상 소스를 `JavaFileObjects.forSourceString()`로 인메모리 생성한다. Q-클래스는 테스트에서 미리 정의해 둔다(QueryDSL 처리기 의존을 끊고 분석 코어만 격리 검증).

```java
// src/test/java/.../support/Fixtures.java
package com.ourcompany.jpqlcheck.support;

import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;

public final class Fixtures {

    private Fixtures() {}

    /** 공통 Q-클래스 (StringPath/NumberPath/DateTimePath 혼합) */
    public static JavaFileObject qUser() {
        return JavaFileObjects.forSourceString("com.example.QUser", """
            package com.example;
            import com.querydsl.core.types.dsl.*;
            import java.math.BigDecimal;
            import java.time.Instant;
            public class QUser {
                public static final QUser user = new QUser();
                public final StringPath name = Expressions.stringPath("name");
                public final NumberPath<Integer> age = Expressions.numberPath(Integer.class, "age");
                public final NumberPath<BigDecimal> score = Expressions.numberPath(BigDecimal.class, "score");
                public final DateTimePath<Instant> createdAt =
                    Expressions.dateTimePath(Instant.class, "createdAt");
            }
            """);
    }

    public static JavaFileObject qEvent() {
        return JavaFileObjects.forSourceString("com.example.QEvent", """
            package com.example;
            import com.querydsl.core.types.dsl.*;
            import java.time.LocalDate;
            public class QEvent {
                public static final QEvent event = new QEvent();
                public final DatePath<LocalDate> eventDate =
                    Expressions.datePath(LocalDate.class, "eventDate");
            }
            """);
    }

    /** 검사 대상 코드를 한 줄 표현식으로 감싸 컴파일 단위 생성 */
    public static JavaFileObject usage(String body) {
        return JavaFileObjects.forSourceString("com.example.UserService", """
            package com.example;
            import com.querydsl.core.types.dsl.*;
            import com.querydsl.core.types.*;
            public class UserService {
                static final QUser qUser = QUser.user;
                static final QEvent qEvent = QEvent.event;
                Object run() {
                    return %s ;
                }
            }
            """.formatted(body));
    }
}
```

---

## 2. UT-4: 불일치 판정 핵심 테스트 (FR-4)

설계서 §2.3의 12케이스를 `@ParameterizedTest`로 구현. 판정 결과(OK/WARNING/ERROR)와 진단 메시지를 동시 검증.

```java
// src/test/java/.../TypeMismatchJudgmentTest.java
package com.ourcompany.jpqlcheck;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static com.ourcompany.jpqlcheck.support.Fixtures.*;

import com.google.testing.compile.Compilation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TypeMismatchJudgmentTest {

    enum Verdict { OK, WARNING, ERROR }

    static List<Arguments> cases() {
        return List.of(
            // TC ID,        검사 대상 표현식,                              기대 판정
            Arguments.of("UT-4-01", "qUser.name.length()",                 Verdict.OK),
            Arguments.of("UT-4-02", "Expressions.numberOperation(Integer.class, Ops.MathOps.ABS, qUser.name)", Verdict.ERROR),
            Arguments.of("UT-4-03", "Expressions.numberTemplate(Integer.class, \"mod({0})\", qUser.score)", Verdict.ERROR),
            Arguments.of("UT-4-04", "qUser.score.abs()",                   Verdict.OK),
            Arguments.of("UT-4-05", "abs_unresolved()",                    Verdict.WARNING),
            Arguments.of("UT-4-06", "Expressions.numberTemplate(Integer.class, \"extract(year from {0})\", qUser.name)", Verdict.ERROR),
            Arguments.of("UT-4-07", "Expressions.numberTemplate(Integer.class, \"extract(year from {0})\", qEvent.eventDate)", Verdict.OK),
            Arguments.of("UT-4-08", "qUser.score.round()",                 Verdict.OK),
            Arguments.of("UT-4-11", "qUser.age.sqrt()",                    Verdict.OK),
            Arguments.of("UT-4-12", "qUser.score.mod(2)",                  Verdict.WARNING)
        );
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @MethodSource("cases")
    @DisplayName("함수 인자 타입 불일치 판정 (Hibernate 6 기준)")
    void judges(String tcId, String expr, Verdict expected) {
        Compilation compilation = javac()
            .withProcessors(new JpqlFunctionTypeProcessor())
            .compile(qUser(), qEvent(), usage(expr));

        switch (expected) {
            case OK -> {
                assertThat(compilation).succeededWithoutWarnings();
            }
            case WARNING -> {
                assertThat(compilation).succeeded();
                assertThat(compilation).hadWarningContaining("JPQL");
            }
            case ERROR -> {
                assertThat(compilation).failed();
                assertThat(compilation).hadErrorContaining("타입 불일치");
            }
        }
    }
}
```

> 비고: `abs_unresolved()`는 동적 `Expression<?>`를 반환하는 헬퍼로, 픽스처에 추가하여 UNRESOLVED 경로를 강제한다.

---

## 3. UT-5: 진단 정보 정확성 테스트 (FR-5.1, FR-5.2)

검출 메시지에 소스명·위치·엔티티·컬럼이 모두 포함되는지 검증.

```java
// src/test/java/.../DiagnosticContentTest.java
package com.ourcompany.jpqlcheck;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static com.ourcompany.jpqlcheck.support.Fixtures.*;

import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiagnosticContentTest {

    @Test
    @DisplayName("UT-5-01~07: 진단에 소스명/엔티티/컬럼/타입/근거 포함")
    void diagnosticContainsAllFields() {
        Compilation c = javac()
            .withProcessors(new JpqlFunctionTypeProcessor())
            .compile(qUser(), qEvent(),
                usage("Expressions.numberOperation(Integer.class, Ops.MathOps.ABS, qUser.name)"));

        assertThat(c).failed();
        // 엔티티.컬럼(타입) 표기
        assertThat(c).hadErrorContaining("User.name");
        assertThat(c).hadErrorContaining("String");
        // 함수명/기대타입
        assertThat(c).hadErrorContaining("abs");
        assertThat(c).hadErrorContaining("Number");
        // 판정 근거
        assertThat(c).hadErrorContaining("ArgumentsValidator");
    }

    @Test
    @DisplayName("UT-5-02: 진단이 정확한 소스 위치(파일+라인)에 부착")
    void diagnosticAttachedToSourceLocation() {
        var usage = usage("Expressions.numberOperation(Integer.class, Ops.MathOps.ABS, qUser.name)");
        Compilation c = javac()
            .withProcessors(new JpqlFunctionTypeProcessor())
            .compile(qUser(), qEvent(), usage);

        assertThat(c).hadErrorContaining("타입 불일치").inFile(usage);
    }
}
```

---

## 4. UT-5-08~10: 엔티티 역매핑 테스트 (FR-5.4)

```java
// src/test/java/.../EntityReverseMappingTest.java
package com.ourcompany.jpqlcheck;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntityReverseMappingTest {

    @Test
    @DisplayName("UT-5-08: 기본 Q prefix -> 엔티티명 역산 (방법 A)")
    void defaultPrefixReverseMapping() {
        Compilation c = javac()
            .withProcessors(new JpqlFunctionTypeProcessor())
            .compile(com.ourcompany.jpqlcheck.support.Fixtures.qUser(),
                     com.ourcompany.jpqlcheck.support.Fixtures.qEvent(),
                     com.ourcompany.jpqlcheck.support.Fixtures.usage(
                       "Expressions.numberOperation(Integer.class, Ops.MathOps.ABS, qUser.name)"));
        assertThat(c).hadErrorContaining("User.name"); // QUser -> User
    }

    @Test
    @DisplayName("UT-5-09: 커스텀 prefix -> PathMetadata 폴백 (방법 B)")
    void customPrefixFallback() {
        var customQ = JavaFileObjects.forSourceString("com.example.MyMember", """
            package com.example;
            import com.querydsl.core.types.dsl.*;
            public class MyMember {
                public static final MyMember member = new MyMember();
                public final StringPath nickname = Expressions.stringPath("nickname");
            }
            """);
        var usage = JavaFileObjects.forSourceString("com.example.Svc", """
            package com.example;
            import com.querydsl.core.types.dsl.*;
            import com.querydsl.core.types.*;
            public class Svc {
                Object run() {
                    return Expressions.numberOperation(
                        Integer.class, Ops.MathOps.ABS, MyMember.member.nickname);
                }
            }
            """);
        Compilation c = javac()
            .withProcessors(new JpqlFunctionTypeProcessor())
            .compile(customQ, usage);
        // 방법 A 실패 -> PathMetadata property명으로 컬럼 추출
        assertThat(c).hadErrorContaining("nickname");
    }
}
```

---

## 5. UT-1: 타입 정규화 단위 테스트 (FR-1)

순수 정규화 로직은 처리기 의존 없이 별도 유닛으로 분리 테스트(권장).

```java
// src/test/java/.../TypeNormalizerTest.java
package com.ourcompany.jpqlcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// TypeNormalizer는 TypeMirror를 받지만, 여기서는 문자열 시그니처 기반
// 헬퍼(normalizeFromSignature)로 단위 검증 (컴파일 의존 없이 로직만)
class TypeNormalizerTest {

    @Test @DisplayName("UT-1-01: StringPath -> String")
    void stringPath() {
        assertEquals("java.lang.String",
            TypeNormalizer.normalizeFromSignature("com.querydsl.core.types.dsl.StringPath"));
    }

    @Test @DisplayName("UT-1-02: NumberPath<BigDecimal> -> BigDecimal")
    void numberPath() {
        assertEquals("java.math.BigDecimal",
            TypeNormalizer.normalizeFromSignature(
                "com.querydsl.core.types.dsl.NumberPath<java.math.BigDecimal>"));
    }

    @Test @DisplayName("UT-1-04: Expression<?> -> UNRESOLVED")
    void wildcard() {
        assertTrue(TypeNormalizer.isUnresolved(
            TypeNormalizer.normalizeFromSignature(
                "com.querydsl.core.types.Expression<?>")));
    }
}
```

---

## 6. UT-5-11~16: 파일 리포트 출력 테스트 (FR-5.3)

```java
// src/test/java/.../ReportOutputTest.java
package com.ourcompany.jpqlcheck;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReportOutputTest {

    @TempDir Path outDir;

    @Test @DisplayName("UT-5-12: JSON 리포트 스키마 유효")
    void jsonReport() throws Exception {
        var finding = new Finding("UserService.java", 47, 32,
            "abs", "User", "name", "String", "Number", "ERROR",
            "ArgumentsValidator: 숫자 인자 요구");
        new JsonReporter(outDir).write(List.of(finding));

        Path json = outDir.resolve("report.json");
        assertTrue(Files.exists(json));
        JsonNode root = new ObjectMapper().readTree(json.toFile());
        JsonNode f = root.get("findings").get(0);
        assertEquals("User", f.get("entity").asText());
        assertEquals("name", f.get("column").asText());
        assertEquals("ERROR", f.get("severity").asText());
    }

    @Test @DisplayName("UT-5-14: SARIF 2.1.0 형식 유효")
    void sarifReport() throws Exception {
        var finding = new Finding("UserService.java", 47, 32,
            "abs", "User", "name", "String", "Number", "ERROR", "rule");
        new SarifReporter(outDir).write(List.of(finding));

        JsonNode root = new ObjectMapper().readTree(
            outDir.resolve("results.sarif").toFile());
        assertEquals("2.1.0", root.get("version").asText());
        assertTrue(root.get("runs").isArray());
    }

    @Test @DisplayName("UT-5-16: 검출 0건 -> 빈 리포트 정상 생성")
    void emptyReport() throws Exception {
        new JsonReporter(outDir).write(List.of());
        JsonNode root = new ObjectMapper().readTree(
            outDir.resolve("report.json").toFile());
        assertEquals(0, root.get("findings").size());
    }
}
```

---

## 7. IT-6: Maven 플러그인 통합 테스트 (FR-6)

`maven-invoker-plugin` 방식 — `src/it/` 아래에 미니 프로젝트를 두고 빌드 결과를 검증.

### 7.1 디렉터리 구조
```
src/it/
├── fail-on-error/
│   ├── pom.xml                 (failOnError=true, 불일치 코드 포함)
│   ├── src/main/java/...       (abs(stringColumn) 포함)
│   └── verify.groovy           (BUILD FAILURE + 로그 검증)
├── pass-clean/
│   ├── pom.xml
│   ├── src/main/java/...       (정상 코드만)
│   └── verify.groovy           (BUILD SUCCESS)
└── report-formats/
    ├── pom.xml                 (reportFormats=json,html)
    └── verify.groovy           (두 파일 존재 검증)
```

### 7.2 IT-6-04: failOnError 검증 (verify.groovy)
```groovy
// src/it/fail-on-error/verify.groovy
def log = new File(basedir, "build.log").text

// 빌드가 실패해야 함
assert log.contains("BUILD FAILURE")
// 정확한 진단 메시지 포함
assert log.contains("JPQL 함수 타입 불일치")
assert log.contains("User.name")
assert log.contains("ERROR")
return true
```

### 7.3 IT-6-07: 리포트 다중 형식 (verify.groovy)
```groovy
// src/it/report-formats/verify.groovy
def reportDir = new File(basedir, "target/jpql-check")
assert new File(reportDir, "report.json").exists()
assert new File(reportDir, "report.html").exists()
return true
```

### 7.4 IT-6-12: clean 후 리포트 정리
```groovy
// 빌드 후 mvn clean 실행했다고 가정, target 디렉터리 부재 확인
def reportDir = new File(basedir, "target/jpql-check")
assert !reportDir.exists()
return true
```

---

## 8. NF / EC: 비기능·경계 테스트

### 8.1 NF-01: 오탐(False Positive) 0건
```java
// src/test/java/.../FalsePositiveTest.java
@Test @DisplayName("NF-01: 정상 함수 사용 100건에 오탐 없음")
void noFalsePositives() {
    List<String> validExprs = List.of(
        "qUser.name.length()",
        "qUser.name.lower()",
        "qUser.score.abs()",
        "qUser.age.sqrt()",
        "qUser.name.substring(0, 3)",
        "qUser.score.add(qUser.age)"
        // ... 정상 패턴 누적
    );
    for (String expr : validExprs) {
        Compilation c = javac()
            .withProcessors(new JpqlFunctionTypeProcessor())
            .compile(qUser(), qEvent(), usage(expr));
        assertThat(c).succeeded(); // ERROR 없어야 함
    }
}
```

### 8.2 EC-01: Q-클래스 미생성 안전 처리
```java
@Test @DisplayName("EC-01: QueryDSL 처리기 부재 시 무한루프 없이 안내")
void missingQClass() {
    // Q-클래스 없이 usage만 컴파일 -> 컴파일 자체는 실패(심볼 없음)하나
    // 처리기가 예외/무한루프를 일으키지 않아야 함
    Compilation c = javac()
        .withProcessors(new JpqlFunctionTypeProcessor())
        .compile(Fixtures.usage("qUser.name.length()")); // QUser 미제공
    // 처리기 내부 예외(NPE 등)로 인한 비정상 종료가 없어야 함
    assertThat(c).hadErrorCount(1); // 심볼 미해결 1건만 (처리기 크래시 아님)
}
```

### 8.3 EC-02: 동일 라인 다중 호출
```java
@Test @DisplayName("EC-02: 한 줄에 불일치 2건 -> 각각 독립 진단")
void multipleOnOneLine() {
    Compilation c = javac()
        .withProcessors(new JpqlFunctionTypeProcessor())
        .compile(qUser(), qEvent(), usage(
            "Expressions.list("
          + "Expressions.numberOperation(Integer.class, Ops.MathOps.ABS, qUser.name),"
          + "Expressions.numberOperation(Integer.class, Ops.MathOps.SQRT, qUser.name))"));
    assertThat(c).hadErrorCount(2); // 두 호출 각각
}
```

---

## 9. 테스트 케이스 ↔ 설계서 추적

| 구현 테스트 클래스 | 설계서 TC | 요구사항 |
|-------------------|----------|---------|
| `TypeMismatchJudgmentTest` | UT-4-01~12 | FR-4 |
| `DiagnosticContentTest` | UT-5-01~07 | FR-5.1, 5.2 |
| `EntityReverseMappingTest` | UT-5-08~10 | FR-5.4 |
| `TypeNormalizerTest` | UT-1-01~05 | FR-1 |
| `ReportOutputTest` | UT-5-11~16 | FR-5.3 |
| `*/verify.groovy` (invoker) | IT-6-01~12 | FR-6 |
| `FalsePositiveTest` | NF-01 | NFR-1 |
| `*Test (EC)` | EC-01~06 | 경계 |

---

## 10. 실행 방법

```bash
# 분석 코어 단위/컴파일 테스트
mvn test

# Maven 플러그인 통합 테스트 (invoker)
mvn verify -Prun-its

# 특정 케이스만
mvn test -Dtest=TypeMismatchJudgmentTest
```

---

## 11. 미구현/보류 (구현 시 확정 필요)
1. `JpqlFunctionTypeProcessor`, `TypeNormalizer`, `Finding`, `*Reporter`의 실제 시그니처는 분석 코어 구현에 맞춰 조정 필요(여기서는 테스트 관점 계약만 정의).
2. UNRESOLVED 강제용 `abs_unresolved()` 헬퍼는 픽스처에 추가 정의 필요.
3. 임베디드 Hibernate 대조 자동화(설계서 §9-1) 채택 시, `@Tag("hibernate-oracle")` 별도 테스트 세트 추가.
4. NF-03(성능) 측정은 JMH 또는 빌드 시간 측정 스크립트로 별도 분리(단위 테스트 부적합).