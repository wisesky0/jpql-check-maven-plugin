# PRD: QueryDSL Template 기반 JPQL 타입 불일치 런타임 오류 정적 감지

> **버전**: 3.1
> **작성일**: 2026-06-10
> **변경 이력**:
> - 3.0 — 신규 작성 (이전 버전 미참조)
> - 3.1 — 비교 대상 유형(리터럴/Constant/Path)에 따른 오류 발생 조건 반영, 판정 알고리즘·시스템 구성·수용 기준 추가 (개발 착수 가능 수준으로 보완)

---

## 1. 배경

다음과 같이 프로젝트 스택을 마이그레이션하였다.

| 구분 | 마이그레이션 전 | 마이그레이션 후 |
|---|---|---|
| Spring Data JPA | 2.3.3.RELEASE (Hibernate 5.4.20.Final) | 3.5.11 (Hibernate 6.6.49.Final) |
| Java | 8 | 17 |
| QueryDSL | 4.3.1 | 5.1.0 |
| 빌드 도구 | Maven | Maven |

Hibernate 6.6.49.Final부터 JPQL(HQL) 쿼리 컴파일 시
`org.hibernate.query.sqm.internal.TypecheckUtil`이 **표현식 간 타입 호환성을 엄격하게 검사**한다.
Hibernate 5.4.20.Final에서는 통과하던 QueryDSL `Expressions.*Template(...)` 기반 쿼리가
마이그레이션 후 **런타임에 예외를 발생**시키는 문제가 확인되었다.

이 오류는 컴파일 타임에는 드러나지 않고 해당 쿼리가 실제 실행될 때만 발생하므로,
**빌드 시점에 소스 코드를 정적 분석하여 위험 패턴을 사전 감지**하는 것이 본 과제의 목적이다.

---

## 2. 용어 정의

| 용어 | 정의 |
|---|---|
| **템플릿 표현식** | `Expressions.template(..)` 계열 메서드로 생성된 QueryDSL 표현식. 템플릿 문자열(예: `"ABS({0})"`)과 바인딩 인자들로 구성된다. |
| **Path 계열** | `com.querydsl.core.types.Path<T>`의 구현/확장 타입. Q클래스의 컬럼 경로(`qEntity.column`, `StringPath`, `NumberPath` 등)가 해당한다. HQL 렌더링 시 **타입이 확정된 식별자 표현식**이 된다. |
| **Constant 계열** | `com.querydsl.core.types.Constant<T>`(`ConstantImpl.create(..)`) 및 Java 리터럴/변수 값. HQL 렌더링 시 **바인드 파라미터**(`?1`)가 된다. |
| **등록 함수** | Hibernate 6.6.49.Final의 함수 레지스트리에 시그니처(인자 타입·반환 타입)가 정의된 JPQL/HQL 함수 (예: `ABS`, `LOWER`, `SUBSTRING`). |
| **미등록 함수** | Hibernate 레지스트리에 없는 DB 네이티브 함수 (예: MySQL `DATE_FORMAT`). 반환 타입을 알 수 없어 **`Object`로 추론**된다. |
| **TypecheckUtil 검사** | 쿼리 실행 시 HQL→SQM 컴파일 과정에서 수행되는 피연산자 타입 호환성 검사. 불일치 시 `SemanticException` 계열 예외가 발생한다. |

---

## 3. 문제 정의

### 3.1 핵심 판정 원리 (두 오류의 공통 메커니즘)

TypecheckUtil의 타입 검사는 **피연산자의 타입이 정적으로 확정된 경우에만 발동**한다.

- **Path 계열** 피연산자 → 엔티티 매핑에서 타입이 확정됨 → **검사 발동, 불일치 시 예외**
- **Constant 계열** 피연산자 → 바인드 파라미터로 렌더링되어 타입이 유연하게 추론됨 → **검사 미발동, 오류 없음**

따라서 본 도구의 감지 규칙은 "템플릿에 어떤 함수가 쓰였는가"뿐 아니라
**"문제 위치의 피연산자가 Path 계열인가, Constant 계열인가"를 반드시 구분**해야 한다.
이를 구분하지 않으면 대량의 오탐(false positive)이 발생한다.

### 3.2 오류 1 — 등록 함수의 인자 타입 불일치

```java
// 문자열 컬럼(Path)을 숫자 함수 ABS()의 인자로 전달 → 런타임 예외
Expressions.numberTemplate(Long.class, "ABS({0})", qEntity.stringColumn)
```

- **발생 지점**: `org.hibernate.query.sqm.internal.TypecheckUtil`
- **원인**: Hibernate 6.6.49.Final은 등록 함수(`ABS` 등)의 시그니처를 알고 있으며,
  선언된 인자 타입(숫자)과 실제 전달된 표현식 타입(`String`)이 불일치하면 예외를 던진다.
- **발생 조건**: 함수 인자 위치에 바인딩된 것이 **타입 비호환 Path 계열**일 때.
  Constant 계열 인자는 바인드 파라미터로 처리되어 타입이 함수 시그니처에 맞게 추론되므로 발생하지 않는다.

### 3.3 오류 2 — 미등록 함수 결과(Object 추론)와 타입 확정 표현식의 비교

미등록 함수를 포함한 템플릿 표현식은 QueryDSL 상으로는 `StringExpression`이지만,
Hibernate가 보는 HQL 타입은 `Object`다. 이 표현식을 **비교 연산**(`goe`, `gt`, `loe`, `lt`, `eq`, `ne`, `between` 등)으로
타입이 확정된 표현식과 결합하면 `Object` vs 확정 타입 불일치 예외가 발생한다.

**비교 대상 유형에 따른 발생 여부 (실측 결과)**:

```java
// (a) String 값과 비교 → 바인드 파라미터 → 오류 없음
String value = "20210901";
Expressions.stringTemplate("DATE_FORMAT({0}, {1})", qEntity.column, "%Y%m%d").goe(value);

// (b) Constant와 비교 → 바인드 파라미터 → 오류 없음
Constant<String> constant = ConstantImpl.create("20201212");
Expressions.stringTemplate("DATE_FORMAT({0}, {1})", qEntity.column, "%Y%m%d").goe(constant);

// (c) Path 계열과 비교 → 타입 확정 표현식 → 런타임 예외 발생
StringPath right = Expressions.stringPath("20201212");
Expressions.stringTemplate("DATE_FORMAT({0}, {1})", qEntity.column, "%Y%m%d").goe(right);

// (c') 엔티티 컬럼 Path와 비교 → 동일하게 런타임 예외 발생
Expressions.stringTemplate("DATE_FORMAT({0}, {1})", qEntity.column, "%Y%m%d").goe(qEntity.stringDttm);

// (c'') 비교 방향이 반대여도 동일하게 발생
qEntity.stringDttm.goe(Expressions.stringTemplate("DATE_FORMAT({0}, {1})", qEntity.column, "%Y%m%d"));
```

| 비교 대상 | HQL 렌더링 | TypecheckUtil 검사 | 결과 |
|---|---|---|---|
| Java 값 / 리터럴 (a) | 바인드 파라미터 | 미발동 | 정상 |
| `Constant<T>` (b) | 바인드 파라미터 | 미발동 | 정상 |
| `Path<T>` 확장 (c, c', c'') | 타입 확정 표현식 | 발동 | **예외** |

> 참고: 사례 (c)의 실측 예외 메시지는 `Object` vs `Integer` 불일치였다.
> `stringPath("20201212")`의 경로명이 HQL에서 숫자 리터럴로 해석된 것으로 보이나,
> 본질은 동일하다 — **비교 대상이 Path 계열이면 타입 검사가 발동되어 `Object`와의 비교가 실패**한다.

- **발생 조건 요약**: 미등록 함수 템플릿 표현식이 `where`/`having`에서
  **Path 계열(또는 타입이 확정된 표현식)과 비교 연산으로 결합**될 때.
  비교 대상이 좌변이든 우변이든, 함수 인자가 바인딩(`{1}`)이든 템플릿 내 리터럴(`'%Y%m%d'`)이든 동일하게 발생한다.

---

## 4. 요구사항

### 4.1 분석(사전 조사) 요구사항

| ID | 요구사항 |
|---|---|
| R-01 | QueryDSL 5.1.0의 `Expressions.template(..)` 계열 메서드 전체를 나열한다. (`template`, `simpleTemplate`, `stringTemplate`, `numberTemplate`, `booleanTemplate`, `dateTemplate`, `timeTemplate`, `dateTimeTemplate`, `comparableTemplate`, `enumTemplate` 등) |
| R-02 | R-01의 각 메서드를 **오류 1 발생 가능 / 오류 2 발생 가능 / 해당 없음**으로 분류하고 근거를 문서화한다. |
| R-03 | Hibernate 6.6.49.Final (Spring Data JPA 3.5.11 기준)가 인식하는 등록 함수와 각 함수의 파라미터 타입·반환 타입을 나열하여 **함수 카탈로그**로 정리한다. |
| R-04 | R-03 카탈로그 기준으로 각 함수의 오류 1 / 오류 2 유발 가능성을 분류하고, 감지 규칙(D 계열)의 판정 데이터로 반영한다. 카탈로그에 없는 함수명은 미등록 함수로 취급한다. |

### 4.2 감지 규칙 요구사항

| ID | 요구사항 |
|---|---|
| D-01 | **오류 1 감지**: 템플릿 문자열 내 등록 함수의 인자 위치에 바인딩된 표현식이 **Path 계열이고**, 그 Java 타입이 함수 카탈로그의 선언 인자 타입과 호환되지 않으면 감지한다. (예: `ABS({0})`에 `StringPath` 바인딩) |
| D-02 | **오류 1 미감지 조건**: 함수 인자가 Constant 계열(리터럴, `ConstantImpl`, Java 값)이면 바인드 파라미터로 처리되므로 감지하지 않는다. |
| D-03 | **오류 2 감지**: 미등록 함수를 포함한 템플릿 표현식이 비교 연산(`goe`, `gt`, `loe`, `lt`, `eq`, `ne`, `between`, `in` 등)으로 **Path 계열 표현식과 결합**되면 감지한다. |
| D-04 | **오류 2 미감지 조건**: 비교 대상이 Constant 계열(Java 값, 리터럴, `ConstantImpl.create(..)`)이면 감지하지 않는다. (3.3의 (a), (b) 사례 — 정상 동작) |
| D-05 | D-03은 비교 연산의 **좌변/우변 어느 쪽에 템플릿이 위치하든** 감지해야 한다. (템플릿`.goe(path)` 형태와 path`.goe(템플릿)` 형태 모두) |
| D-06 | D-03은 미등록 함수의 인자가 파라미터 바인딩(`{1}`)이든 템플릿 내 리터럴(`'%Y%m%d'`)이든 동일하게 감지해야 한다. |
| D-07 | 감지는 **비교 연산이 결합되는 지점**에서 수행한다. `where`/`having` 절 전달 여부의 데이터플로 추적은 요구하지 않는다(보수적 근사). |

### 4.3 감지 제외(우회 패턴) 요구사항

이미 우회 조치가 적용된 코드는 오탐을 막기 위해 감지에서 제외한다.

| ID | 요구사항 |
|---|---|
| E-01 | **오류 1 우회 — `cast` 적용 제외**: 함수 인자에 `cast(.. as ..)`가 적용되어 타입이 명시적으로 변환된 경우 감지하지 않는다. 예: `Expressions.numberTemplate(Long.class, "ABS(cast({0} as long))", qEntity.stringColumn)` |
| E-02 | **오류 2 우회 — `function(...)` 구문 제외**: JPQL 표준 함수 호출 구문 `function('함수명', ...)`으로 작성된 경우 감지하지 않는다. 예: `Expressions.stringTemplate("function('DATE_FORMAT', {0}, {1})", qEntity.column, "%Y%m%d")` |

---

## 5. 판정 알고리즘

각 `Expressions.*Template(..)` 호출 지점에 대해 다음 순서로 판정한다.

```
입력: 템플릿 문자열 T, 바인딩 인자 목록 A[0..n], 해당 표현식의 사용 문맥 C

1. T를 파싱하여 함수 호출 목록 F를 추출한다.
   - function('NAME', ...) 구문이 사용된 함수는 F에서 제외한다.        [E-02]

2. F의 각 함수 f에 대해:
   a. f가 함수 카탈로그(R-03)에 존재하면 → [오류 1 검사]
      - f의 인자 위치에 바인딩된 A[i]를 식별한다.
      - A[i]에 cast(.. as ..)가 적용되어 있으면 통과한다.            [E-01]
      - A[i]가 Constant 계열이면 통과한다.                           [D-02]
      - A[i]가 Path 계열이고 그 타입이 f의 선언 인자 타입과
        비호환이면 → Finding(오류 1) 보고                            [D-01]
   b. f가 카탈로그에 없으면(미등록 함수) → [오류 2 검사]
      - 이 템플릿 표현식이 비교 연산으로 결합되는 지점 C를 본다.    [D-07]
      - 비교 대상이 Constant 계열이면 통과한다.                      [D-04]
      - 비교 대상이 Path 계열이면 → Finding(오류 2) 보고
        (좌변/우변 무관 [D-05], f의 인자 형태 무관 [D-06])

3. 모든 Finding을 리포터로 출력한다.
```

**타입 호환성 판정 (D-01)**: Path의 Java 타입과 카탈로그의 인자 타입을
타입 카테고리(NUMERIC, STRING, DATE, TIME, DATETIME, BOOLEAN, ENUM 등)로 정규화한 뒤 카테고리 일치 여부로 판정한다.
숫자형 간(`Integer`/`Long`/`BigDecimal` 등)은 호환으로 본다.

---

## 6. 시스템 구성 및 구현 요구사항

본 도구는 기존 2모듈 구조를 따른다.

| 모듈 | 역할 |
|---|---|
| `jpql-check-processor` | javac 어노테이션 프로세서 + Compiler Tree API 기반 소스 AST 분석. 템플릿 호출 탐지, 판정 알고리즘(5장), 함수 카탈로그, 리포터(JSON/HTML/SARIF) 구현 |
| `jpql-check-maven-plugin` | Maven Mojo. 대상 프로젝트 빌드에 프로세서를 연결하고 설정 파라미터를 전달, 결과에 따라 빌드 실패 처리 |

### 6.1 구현 요구사항

| ID | 요구사항 |
|---|---|
| I-01 | 분석은 컴파일 시점 AST 기반으로 수행하며, 대상 프로젝트의 소스 수정이나 런타임 의존성 추가를 요구하지 않는다. |
| I-02 | 함수 카탈로그(R-03 산출물)는 코드와 분리된 데이터(예: 리소스 파일)로 관리하여 함수 추가·수정 시 재컴파일 없이 갱신 가능해야 한다. |
| I-03 | 템플릿 문자열이 **컴파일 타임 상수가 아닌 경우**(변수 조합 등) 판정 불가로 분류하고, 별도 심각도(`WARN`)로 보고한다. 누락(silent skip)시키지 않는다. |
| I-04 | Maven 설정 파라미터를 제공한다: 검사 대상/제외 경로, 보고서 출력 형식(json/html/sarif)·경로, 오류 발견 시 빌드 실패 여부(`failOnError`), 추가 미등록 함수명 목록. |
| I-05 | Finding은 최소한 다음을 포함한다: 규칙 ID(D-01/D-03 등), 심각도, 소스 파일·라인, 템플릿 문자열 원문, 문제 인자/비교 대상의 표현식과 추론 타입, 권장 우회 방법(E-01 또는 E-02 형식 예시). |

---

## 7. 수용 기준 (테스트 케이스 매트릭스)

| TC | 시나리오 | 기대 결과 |
|---|---|---|
| TC-01 | `numberTemplate(Long.class, "ABS({0})", stringPath)` | **감지** (D-01) |
| TC-02 | `numberTemplate(Long.class, "ABS({0})", numberPath)` | 미감지 (타입 호환) |
| TC-03 | `numberTemplate(Long.class, "ABS({0})", 상수/리터럴)` | 미감지 (D-02) |
| TC-04 | `numberTemplate(Long.class, "ABS(cast({0} as long))", stringPath)` | 미감지 (E-01) |
| TC-05 | `stringTemplate("DATE_FORMAT({0},{1})", path, "%Y%m%d").goe(qEntity.stringDttm)` | **감지** (D-03) |
| TC-06 | `qEntity.stringDttm.goe(stringTemplate("DATE_FORMAT(..)" ..))` (좌우 반전) | **감지** (D-05) |
| TC-07 | `stringTemplate("DATE_FORMAT({0}, '%Y%m%d')", path).goe(path2)` (리터럴 포맷) | **감지** (D-06) |
| TC-08 | `stringTemplate("DATE_FORMAT(..)").goe("20210901")` (String 값 비교) | 미감지 (D-04) |
| TC-09 | `stringTemplate("DATE_FORMAT(..)").goe(ConstantImpl.create("20201212"))` | 미감지 (D-04) |
| TC-10 | `stringTemplate("DATE_FORMAT(..)").goe(Expressions.stringPath("x"))` | **감지** (D-03, Path 계열) |
| TC-11 | `stringTemplate("function('DATE_FORMAT', {0}, {1})", ..).goe(path)` | 미감지 (E-02) |
| TC-12 | 미등록 함수 템플릿을 비교 없이 select 절에서만 사용 | 미감지 (비교 결합 없음) |
| TC-13 | 템플릿 문자열이 비상수(변수 조합) | WARN 보고 (I-03) |
| TC-14 | 등록 함수 `LOWER({0})`에 `stringPath` 바인딩 | 미감지 (타입 호환) |

---

## 8. 산출물

1. **분석 문서**
   - QueryDSL 5.1.0 `Expressions.template(..)` 계열 메서드 목록 및 오류 1/오류 2 발생 가능성 분류표 (R-01, R-02)
   - Hibernate 6.6.49.Final 함수 카탈로그 — 함수명, 파라미터 타입, 반환 타입, 오류 유발 가능성 분류 (R-03, R-04)
2. **패턴 감지 구현**: 5장 판정 알고리즘과 D/E 규칙을 구현한 어노테이션 프로세서 및 Maven 플러그인
3. **리포트**: JSON / HTML / SARIF 형식의 Finding 보고서
4. **테스트**: 7장 수용 기준 매트릭스를 검증하는 자동화 테스트

---

## 9. 범위 외 (Out of Scope)

- 감지된 코드의 자동 수정(auto-fix)
- QueryDSL Template 이외 경로의 쿼리(`@Query(nativeQuery = true)`, 문자열 JPQL 등) 분석
- `where`/`having` 전달 여부까지 추적하는 데이터플로 분석 (D-07의 보수적 근사로 갈음)
- Hibernate 6.6.49.Final 이외 버전에 대한 호환성 검증
- 런타임(실행 시점) 검증 — 본 도구는 빌드 시점 정적 분석만 수행한다
