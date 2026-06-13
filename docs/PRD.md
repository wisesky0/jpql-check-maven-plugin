
# JPQL 함수 인자 타입 불일치 정적 탐지 도구 요구사항 명세서

> **버전**: 3.0
> **기준**: 실제 API 시그니처 검증 및 런타임 사례(DATE_FORMAT SemanticException) 반영

---

## 1. 개요

### 1.1 목적

QueryDSL `Expressions.*Operation` / `Expressions.*Template` 계열 호출에서 JPQL 함수에 전달하는 인자의 타입이 함수 요구 타입과 불일치할 때, 또는 native 함수의 반환 타입이 비교 피연산자와 불일치할 때 발생하는 런타임 `QueryException` / `SemanticException`을 **컴파일 타임에 정적으로 탐지**한다.

### 1.2 해결하는 문제

QueryDSL `Expressions` 메서드의 인자는 컴파일러가 타입 검증을 하지 못한다.

| 메서드 계열 | 인자 시그니처 | 컴파일 타임 체크 |
|------------|------------|----------------|
| `numberOperation(Class<T>, Operator, Expression<?>...)` | `Expression<?>...` (와일드카드) | **없음** |
| `numberTemplate(Class<T>, String, Object...)` | `Object...` | **없음** |
| `stringTemplate(String, Object...)` | `Object...` | **없음** |
| (모든 `*Operation` / `*Template` 계열) | 위와 동일 | **없음** |

따라서 아래 코드들이 컴파일을 통과하고 런타임에 깨진다.

```java
// abs는 Number 요구 → String 전달 → 런타임 SemanticException
Expressions.numberOperation(Integer.class, Ops.MathOps.ABS, qUser.name);

// template 인자도 동일 — Object...이므로 컴파일 통과
Expressions.numberTemplate(Integer.class, "abs({0})", qUser.name);

// DATE_FORMAT은 Hibernate에 미등록 → Object 반환 → String 비교 실패
Expressions.stringTemplate("DATE_FORMAT({0},{1})", col, fmt).eq(qUser.name);
```

### 1.3 탐지 대상 범위 확정

#### 탐지 대상 (IN SCOPE)

- `Expressions.*Operation(Operator, Expression<?>...)` 계열 전체
- `Expressions.*Template(String|Template, Object...)` 계열 전체

해당 메서드 목록:

| 종류 | 메서드명 |
|------|---------|
| Operation | `operation`, `simpleOperation`, `numberOperation`, `stringOperation`, `booleanOperation`, `predicate`, `comparableOperation`, `dateOperation`, `dateTimeOperation`, `timeOperation`, `enumOperation`, `dslOperation`, `collectionOperation` |
| Template | `template`, `simpleTemplate`, `numberTemplate`, `stringTemplate`, `booleanTemplate`, `comparableTemplate`, `dateTemplate`, `dateTimeTemplate`, `timeTemplate`, `enumTemplate`, `dslTemplate` |

#### 탐지 제외 (OUT OF SCOPE)

- **Q-Entity 경로 빌트인 메서드** (`.abs()`, `.lower()`, `.length()`, `.substring(expr, expr)` 등)  
  → Java 타입 시스템이 receiver/파라미터 타입을 컴파일 타임에 이미 보장한다.  
  예: `StringExpression`에만 `.lower()` 선언 → `NumberPath`에서 호출 시 컴파일 오류.

### 1.4 아키텍처

| 계층 | 모듈 | 역할 |
|------|------|------|
| 분석 코어 | `jpql-check-processor` (Annotation Processor JAR) | AST 순회·타입 해석·불일치 판정 |
| 사용성 래퍼 | `jpql-check-maven-plugin` (Maven Plugin Mojo) | 처리기 주입·설정·리포트·빌드 게이트 |

사용자는 Maven 플러그인만 `pom.xml`에 선언하면 된다.

---

## 2. 기능 요구사항

### FR-1. Q-Entity 컬럼 경로 타입 정규화

- **FR-1.1** `Expressions.*Operation/Template` 호출의 각 인자에서 QueryDSL Path 타입을 Java 도메인 타입으로 정규화한다.

| QueryDSL 타입 | 정규화 결과 |
|--------------|-----------|
| `StringPath` | `java.lang.String` |
| `NumberPath<BigDecimal>` | `java.math.BigDecimal` |
| `DateTimePath<Instant>` | `java.time.Instant` |
| `DatePath<LocalDate>` | `java.time.LocalDate` |
| `BooleanPath` | `java.lang.Boolean` |
| `Expression<?>` (와일드카드) | `UNRESOLVED` |
| raw `Path` (제네릭 소거) | `UNRESOLVED` |

- **FR-1.2** `UNRESOLVED` 인자는 ERROR가 아닌 **WARNING**만 발생시킨다 (오탐 방지).

---

### FR-2. 함수 호출 탐지

#### FR-2.1 Operation 계열

`Expressions.*Operation(Operator op, Expression<?>... args)` 패턴에서:

1. `op` 인자(Enum 상수)로 함수명 추출. 예: `Ops.MathOps.ABS` → `abs`
2. `args` 각 인자의 정규화 타입 추출 (FR-1)

#### FR-2.2 Template 계열

`Expressions.*Template(String template, Object... args)` 패턴에서:

1. **template 문자열 파싱**: 최외곽 함수명 추출.  
   예: `"abs({0})"` → `abs`, `"extract(year from {0})"` → `extract`
2. **placeholder 매핑**: `{0}`, `{1}`, … 인덱스와 실제 args를 매핑.
3. **파싱 불가 시**: SKIP + INFO (런타임 조합·복잡 식 등 오탐 방지).

---

### FR-3. 함수 시그니처 카탈로그

#### FR-3.1 내장 카탈로그

Hibernate 6 / JPA 표준 기준으로 아래 함수를 내장한다. 각 엔트리: `name`, `category`, `minArgs`, `maxArgs`, `argCategories`, `returnCategory`, `source`, `minJpaVersion`.

| 카테고리 | 함수 | minArgs | maxArgs | 인자 타입 규칙 |
|----------|------|---------|---------|--------------|
| NUMERIC | `abs`, `sign`, `floor`, `ceiling` | 1 | 1 | [NUMERIC] |
| NUMERIC | `sqrt`, `exp`, `ln` | 1 | 1 | [NUMERIC] |
| NUMERIC | `power` | 2 | 2 | [NUMERIC, NUMERIC] |
| NUMERIC | `mod` | 2 | 2 | [NUMERIC, NUMERIC] (정수 권장) |
| NUMERIC | `round` | 1 | 2 | [NUMERIC, NUMERIC] (JPA 3.2+) |
| STRING | `length` | 1 | 1 | [STRING] |
| STRING | `lower`, `upper`, `trim` | 1 | 1 | [STRING] |
| STRING | `substring` | 2 | 3 | [STRING, NUMERIC, NUMERIC?] |
| STRING | `locate` | 2 | 3 | [STRING, STRING, NUMERIC?] |
| STRING | `concat` | 2 | ∞ | [STRING, STRING, …] |
| DATETIME | `extract` | 2 | 2 | [ANY, DATETIME] |
| GENERAL | `coalesce` | 2 | ∞ | 동일 카테고리 |
| GENERAL | `nullif` | 2 | 2 | 동일 카테고리 |
| GENERAL | `cast` | 2 | 2 | 제약 없음 |

> Number 계열 간(Integer ↔ BigDecimal)은 호환. 정밀도 손실 가능 조합은 WARNING.

#### FR-3.2 외부 카탈로그 오버라이드

`function-catalog.yaml` 경로를 설정으로 지정하면, 내장 카탈로그에 병합·우선 적용한다. 목적:
- 프로젝트 커스텀 함수 등록
- native 함수에 반환 타입 명시 (FR-4.3 억제/승격 제어)

```yaml
functions:
  - name: date_format
    returnCategory: STRING
    minArgs: 2
    maxArgs: 2
  - name: my_numeric_fn
    returnCategory: NUMERIC
    minArgs: 1
    maxArgs: 1
```

---

### FR-4. 타입 불일치 판정

#### FR-4.1 Operation / Template 인자 타입 검사

| 판정 | 조건 |
|------|------|
| **ERROR** | 함수 요구 카테고리 ≠ 인자 카테고리 (예: NUMERIC 함수에 STRING 인자) |
| **ERROR** | 인자 개수 < minArgs 또는 > maxArgs |
| **WARNING** | 인자가 `UNRESOLVED` (정적 타입 미결정) |
| **WARNING** | 정밀도 손실 가능 암묵 변환 (예: `mod(BigDecimal, ...)`) |
| **WARNING** | 현재 환경 JPA 버전 < 함수 `minJpaVersion` (예: `round` in JPA 3.1 환경) |
| OK | 그 외 |

#### FR-4.2 Template 최외곽 함수가 Hibernate 미등록(native)인 경우 — 비교 피연산자 불일치

**핵심 전제**: `Expressions.*Template(...)` 의 QueryDSL 래퍼 타입 (`stringTemplate` → String, `numberTemplate(Integer.class, ...)` → Integer 등)은 **QueryDSL 클라이언트 측 메타데이터일 뿐이다**. QueryDSL이 생성하는 HQL 텍스트에는 반영되지 않는다. Hibernate는 HQL 문자열을 파싱해 함수 반환 타입을 **자기 함수 레지스트리에서 독립적으로** 결정한다. 미등록 native 함수는 `Object`로 해석된다.

실증 사례:
```java
// stringTemplate → String 선언이지만, DATE_FORMAT이 Hibernate에 미등록
// → Hibernate: DATE_FORMAT 반환타입 = Object
// → Object = String 비교 → SemanticException
Expressions.stringTemplate("DATE_FORMAT({0},{1})", col, fmt).eq(qUser.name);
```

**탐지 조건 (4가지 AND)**:

1. 표현식이 `Expressions.*Template(...)` (래퍼 종류 무관)
2. template 문자열의 **최외곽 토큰이 함수 호출** `F(...)` 형태
3. `F`가 내장 카탈로그 및 외부 카탈로그 **모두에 없음** → Hibernate가 Object로 해석
4. 그 template 표현식이 `.eq()` / `.ne()` / `.gt()` / `.goe()` / `.lt()` / `.loe()` / `between` / `in` 등 **비교·술어의 피연산자**로, **타입이 확정된 상대(Q-path 또는 타입 리터럴)** 와 비교됨

→ **template이 좌항·우항 어느 쪽이든 대칭 검사**

**판정**: **ERROR** — 반드시 런타임 `SemanticException` 유발

**억제 방법**: 외부 카탈로그(FR-3.2)에 `F`의 반환 타입을 등록하면:
- 등록 타입과 비교 상대 타입이 **호환** → 정상 (탐지 안 함)
- 등록 타입과 비교 상대 타입이 **비호환** → **ERROR** 유지

**진단 메시지에 해결책 안내 포함**:
- `function('date_format', ...)` + Dialect 함수 등록(반환 타입 지정), 또는
- `cast(... as 타입)`으로 명시적 타입 부여, 또는
- 외부 카탈로그에 반환 타입 등록 (FR-3.2)

---

### FR-5. 진단 정보 및 출력

#### FR-5.1 진단 정보 항목

각 검출 결과에 다음을 **모두** 포함한다.

| 항목 | 추출 방법 | 예시 |
|------|----------|------|
| 소스 파일명 | `CompilationUnitTree.getSourceFile()` | `UserService.java` |
| 라인·컬럼 | `SourcePositions` + `LineMap` | `[47, 32]` |
| 함수명 | 카탈로그 조회 / template 파싱 | `abs` |
| 엔티티 클래스 | Q-클래스 역매핑 (FR-5.2) | `User` |
| 컬럼명 | Path 식별자 | `name` |
| 인자 인덱스 | 호출 인자 순번 (0-based) | `0` |
| 기대 타입 | 카탈로그 argCategories | `NUMERIC` |
| 실제 타입 | 인자 TypeMirror 정규화 결과 | `java.lang.String` |
| 판정 근거 | 위배 규칙 설명 | `abs는 NUMERIC 인자 요구, STRING 비호환` |
| 심각도 | ERROR / WARNING | `ERROR` |

#### FR-5.2 Q-클래스 역매핑 전략

```
방법 A (기본): Q-클래스 명명 규칙 역산
  QUser → User,  qUser.name → "name"

방법 B (폴백): PathMetadata에서 root 타입·property 추출
  (비표준 prefix 대응)
```

- 방법 A 실패 → 방법 B 적용.
- 둘 다 실패 → 식별자 원문 표기 + WARNING.

#### FR-5.3 진단 메시지 표준 포맷

```
[ERROR] UserService.java:[47,32] JPQL 함수 타입 불일치
  함수       : abs (NUMERIC 카테고리)
  대상       : User.name (java.lang.String)
  인자 인덱스 : 0
  기대 타입   : NUMERIC
  판정 근거   : abs는 NUMERIC 인자 요구, STRING 비호환
```

```
[ERROR] UserService.java:[92,16] JPQL native 함수 반환타입 불일치
  함수       : DATE_FORMAT (Hibernate 미등록 → Object 반환)
  비교 상대  : User.name (java.lang.String)
  판정 근거  : Hibernate는 미등록 함수를 Object로 해석, Object ≠ String 비교 실패
  해결 방법  : 외부 카탈로그에 DATE_FORMAT 반환타입 등록, 또는 cast() 명시
```

#### FR-5.4 출력 채널

| 채널 | 구현 | 출력 위치 | 용도 |
|------|------|----------|------|
| ① 컴파일러 진단 | `Messager.printMessage(Kind, msg, element)` | 빌드 로그 / IDE Problems 창 | 즉각 피드백, 빌드 게이트 |
| ② 파일 리포트 | `processingOver()` 라운드에서 파일 IO | `target/jpql-check/` | CI 아티팩트, 팀 공유 |

- 채널 ①은 항상 활성. `Kind.ERROR`이면 컴파일 실패로 **빌드 자동 차단**.
- 채널 ② 형식: `json` / `html` / `sarif` (다중 동시 출력 허용).
- 리포트 기본 경로: `target/jpql-check/` — `mvn clean` 시 함께 삭제.

---

### FR-6. Maven 플러그인

#### FR-6.1 Goal

- **`check`** goal: 분석 코어 처리기를 컴파일에 주입하고 검사 실행. 기본 라이프사이클 단계: `compile`.

#### FR-6.2 설정 파라미터

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `failOnError` | `true` | ERROR 검출 시 빌드 실패 |
| `failOnWarning` | `false` | WARNING도 빌드 실패로 처리 |
| `reportFormats` | `[json]` | `json` / `html` / `sarif` 다중 지정 |
| `reportDirectory` | `${project.build.directory}/jpql-check` | 리포트 출력 경로 |
| `functionCatalog` | (내장) | 외부 카탈로그 YAML 경로 (FR-3.2) |
| `suppressUnresolved` | `false` | UNRESOLVED WARNING 억제 |

#### FR-6.3 처리기 주입 방식

- **자동(권장)**: 플러그인이 `maven-compiler-plugin`의 `annotationProcessorPaths`에 분석 코어를 QueryDSL 처리기 다음 순서로 자동 등록.
- **수동 대안**: 자동 주입 불가 환경용 수동 등록 가이드 제공.

#### FR-6.4 라운드 순서 보장

- 분석 코어 처리기는 **`processingOver() == true` 인 마지막 라운드에서만** 실제 검사 수행 → QueryDSL Q-클래스 생성 완료 보장.
- `return false`로 애너테이션 비소비 → 다른 처리기 동작 방해 없음.

---

## 3. 비기능 요구사항

| ID | 항목 | 요구 내용 |
|----|------|-----------|
| NFR-1 | 정확성 | 오탐(False Positive) 0건 목표. 타입 미확정은 ERROR 아닌 WARNING. |
| NFR-2 | 성능 | 증분 컴파일 지원. 빌드 시간 오버헤드 +10% 이내. |
| NFR-3 | 호환성 | Java 17+, Maven 3.9+, QueryDSL 5.x, Hibernate 6.x |
| NFR-4 | 확장성 | 외부 카탈로그로 커스텀/native 함수 등록 가능 |
| NFR-5 | 격리성 | 폐쇄망에서 외부 네트워크 없이 동작 (사내 Nexus/Artifactory 배포) |
| NFR-6 | 멀티모듈 | Maven 멀티모듈 프로젝트: 모듈별 독립 실행 + 루트 집계 리포트 |

---

## 4. 판정 예시 (Acceptance Criteria)

### 4.1 Operation 계열 — 인자 타입 검사

| # | 코드 | 판정 | 근거 |
|---|------|------|------|
| O1 | `numberOperation(ABS, qUser.name)` (name=String) | **ERROR** | NUMERIC 요구, STRING 전달 |
| O2 | `numberOperation(ABS, qUser.score)` (score=BigDecimal) | OK | NUMERIC 호환 |
| O3 | `numberOperation(MOD, qOrder.amount)` (인자 1개) | **ERROR** | 개수 불일치 (2 필요) |
| O4 | `numberOperation(ABS, expr)` (expr=Expression<?>) | **WARNING** | UNRESOLVED |
| O5 | `numberOperation(SQRT, qUser.age)` (age=Integer) | OK | Number 계열 호환 |
| O6 | `numberOperation(MOD, qOrder.amount, 2)` (amount=BigDecimal) | **WARNING** | 정밀도 손실 가능 |

### 4.2 Template 계열 — 인자 타입 검사 (카탈로그 등록 함수)

| # | 코드 | 판정 | 근거 |
|---|------|------|------|
| T1 | `numberTemplate(Integer.class, "abs({0})", qUser.name)` | **ERROR** | `{0}`=String, abs NUMERIC 요구 |
| T2 | `numberTemplate(Integer.class, "abs({0})", qUser.score)` | OK | `{0}`=BigDecimal 호환 |
| T3 | `numberTemplate(Integer.class, "mod({0},{1})", qOrder.amount)` (인자 1개) | **ERROR** | placeholder 2개, 인자 1개 |
| T4 | `numberTemplate(Integer.class, "extract(year from {0})", qUser.name)` | **ERROR** | `{0}`=String, DATETIME 요구 |
| T5 | `numberTemplate(Integer.class, "extract(year from {0})", qEvent.eventDate)` | OK | `{0}`=LocalDate 호환 |
| T6 | `stringTemplate("lower({0})", qUser.name)` | OK | `{0}`=String, STRING 호환 |
| T7 | `stringTemplate("lower({0})", qUser.age)` | **ERROR** | `{0}`=Integer, STRING 요구 |

### 4.3 Template 계열 — native 함수 Object 반환 vs 비교 피연산자

> 판정 결정 인자: **template 최외곽 함수의 Hibernate 등록 여부**.  
> QueryDSL 래퍼 타입(`stringTemplate` / `numberTemplate(Integer.class,...)`)은 **무관**.

| # | 코드 | 판정 | 근거 |
|---|------|------|------|
| N1 | `stringTemplate("DATE_FORMAT({0},{1})",c,fmt).eq(qUser.name)` | **ERROR** | DATE_FORMAT 미등록→Object, vs String |
| N2 | `qUser.name.eq(stringTemplate("DATE_FORMAT({0},{1})",c,fmt))` | **ERROR** | 대칭: 우항 template도 동일 |
| N3 | `numberTemplate(Integer.class,"DATE_FORMAT({0},{1})",c,fmt).eq(qUser.age)` | **ERROR** | Class<Integer>여도 래퍼 무관, 미등록→Object |
| N4 | `stringTemplate("lower({0})",qUser.name).eq(qUser.memo)` (memo=String) | OK | `lower` 등록 함수→String 확정, String=String |
| N5 | (외부카탈로그: `DATE_FORMAT→String`) `.eq(qUser.name)` (String) | OK | 카탈로그로 반환타입 확정·호환 |
| N6 | (외부카탈로그: `DATE_FORMAT→String`) `.eq(qUser.age)` (Integer) | **ERROR** | 카탈로그 확정 String vs Integer 비호환 |

---

## 5. 처리 흐름

```
[pom.xml: Maven 플러그인 선언]
        │  ← FR-6.3: 분석 코어를 annotationProcessorPaths에 자동 주입
        ▼
[javac 컴파일 시작]
        │
        ▼
┌──────────────────────────────┐
│ Round 1: QueryDSL APT         │ → Q-클래스 생성
└─────────────┬────────────────┘
              ▼
┌──────────────────────────────┐
│ Round N: 분석 코어 처리기      │
│  - Expressions.*Operation    │ ← FR-2.1: Operator → 함수명 추출
│  - Expressions.*Template     │ ← FR-2.2: template 파싱, placeholder 매핑
│  - 인자 타입 정규화 (FR-1)    │
│  - 카탈로그 조회 (FR-3)       │
└─────────────┬────────────────┘
              ▼
┌──────────────────────────────┐
│ Final Round (processingOver) │ ← FR-6.4: Q-클래스 확정 후 검사
│  ① 인자 타입 판정 (FR-4.1)   │
│  ② native 비교 판정 (FR-4.2) │
└─────────────┬────────────────┘
              ▼
       ┌──────┴──────┐
       ▼             ▼
 ① Messager      ② File Report
 (ERROR/WARN)    target/jpql-check/
 IDE 라인점프     json / html / sarif
 빌드 게이트       CI 아티팩트
       │
       ▼
[Maven 플러그인: failOnError/failOnWarning 게이트]
       │
       ▼
  BUILD FAILURE / SUCCESS
```

---

## 6. pom.xml 구성 예시

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.wisesky0</groupId>
      <artifactId>jpql-check-maven-plugin</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <executions>
        <execution>
          <goals><goal>check</goal></goals>
        </execution>
      </executions>
      <configuration>
        <failOnError>true</failOnError>
        <reportFormats>
          <format>json</format>
          <format>html</format>
        </reportFormats>
        <!-- native 함수 반환타입 등록으로 FR-4.2 오탐 제거 -->
        <functionCatalog>${project.basedir}/function-catalog.yaml</functionCatalog>
      </configuration>
    </plugin>
  </plugins>
</build>
```

---

## 7. 범위 외 (Out of Scope)

- 런타임 동적 `BooleanBuilder` / 문자열 HQL 검사
- DB Dialect별 함수 지원 여부 (특정 DB의 함수 미지원 등)
- SQL 인젝션 등 보안 검사
- Q-Entity 빌트인 메서드(`.abs()`, `.lower()` 등) — Java 타입 시스템이 컴파일 타임 보장

---

## 8. 미결 사항

1. **Operator → 함수명 매핑 완전성**: `Ops.MathOps`, `Ops.StringOps` 등 모든 Ops 상수와 카탈로그 함수명 매핑 목록 완성 필요.
2. **template 파싱 전략**: 정규식 기반 vs 간단 파서 — 중첩 함수(`abs(round({0},2))`) 처리 범위 확정 필요.
3. **외부 카탈로그 Hibernate 버전 동기화**: `CommonFunctionFactory` 등록분 수동 미러링 vs 리플렉션 추출.
4. **멀티모듈 집계 리포트**: `aggregator` goal 분리 여부.
