# PRD: QueryDSL Template 기반 JPQL 타입 불일치 런타임 오류 정적 감지

> **버전**: 3.0 (신규 작성 — 이전 버전 내용 미참조)
> **작성일**: 2026-06-10

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

## 2. 문제 정의

### 2.1 오류 1 — JPQL 표준 함수의 인자 타입 불일치

```java
// 문자열 컬럼을 숫자 함수 ABS()의 인자로 전달
Expressions.numberTemplate(Long.class, "ABS({0})", qEntity.stringColumn)
```

- **발생 지점**: `org.hibernate.query.sqm.internal.TypecheckUtil`
- **원인**: Hibernate 6.6.49.Final은 JPQL에 정의된 함수(`ABS` 등)의 시그니처를 알고 있으며,
  선언된 인자 타입(숫자)과 실제 전달된 표현식 타입(`String`)이 불일치하면 예외를 던진다.
- **특징**: 템플릿 문자열에 적힌 **함수가 요구하는 인자 타입**과
  템플릿에 바인딩된 **QueryDSL 경로(Path)의 Java 타입**이 어긋나는 경우 전반에서 발생 가능.

### 2.2 오류 2 — 미등록 네이티브 함수의 반환 타입을 Object로 추론

```java
// 패턴 A: 파라미터 바인딩 형태
Expressions.stringTemplate("DATE_FORMAT({0}, {1})", qEntity.column, "%Y%m%d")
        .goe(qEntity.stringDttm)

// 패턴 B: 비교 방향이 반대인 형태
qEntity.stringDttm.goe(
        Expressions.stringTemplate("DATE_FORMAT({0}, {1})", qEntity.column, "%Y%m%d"))

// 패턴 C: 포맷을 리터럴로 직접 기입한 형태
Expressions.stringTemplate("DATE_FORMAT({0}, '%Y%m%d')", qEntity.column)
```

- **발생 지점**: `org.hibernate.query.sqm.internal.TypecheckUtil`
- **원인**: Hibernate 6.6.49.Final은 자신에게 등록되지 않은 네이티브 함수(`DATE_FORMAT` 등)의
  반환 타입을 **`Object`로 간주**한다. 이 결과를 `where` / `having` 절에서
  엔티티의 `String` 컬럼과 비교(`goe`, `loe`, `gt`, `lt`, `eq` 등)하면
  `Object` vs `String` 타입 불일치로 예외가 발생한다.
- **특징**: `stringTemplate(...)`이 QueryDSL 상으로는 `StringExpression`이지만,
  Hibernate가 보는 실제 HQL 타입은 `Object`이므로 **비교 연산이 결합될 때만** 오류가 드러난다.
  비교 대상이 좌변이든 우변이든 동일하게 발생한다.

---

## 3. 요구사항

### 3.1 분석(사전 조사) 요구사항

| ID | 요구사항 |
|---|---|
| R-01 | QueryDSL의 `Expressions.template(..)` 계열 메서드 전체를 나열한다. (`template`, `simpleTemplate`, `stringTemplate`, `numberTemplate`, `booleanTemplate`, `dateTemplate`, `timeTemplate`, `dateTimeTemplate`, `comparableTemplate`, `enumTemplate` 등) |
| R-02 | R-01에서 나열한 각 메서드를 **오류 1 발생 가능 / 오류 2 발생 가능 / 해당 없음**으로 분류하고, 분류 근거를 문서화한다. |
| R-03 | Hibernate 6.6.49.Final (Spring Data JPA 3.5.11 기준)가 인식하는 JPQL/HQL 함수와 각 함수의 파라미터 타입·반환 타입을 나열한다. |
| R-04 | R-03의 함수 목록을 기준으로 각 함수가 오류 1 / 오류 2를 유발할 수 있는지 분류하고, 그 결과를 패턴 감지 규칙에 반영한다. |

### 3.2 감지 규칙 요구사항

| ID | 요구사항 |
|---|---|
| D-01 | **오류 1 감지**: 템플릿 문자열 내 JPQL 등록 함수의 인자 위치에 바인딩된 QueryDSL 경로의 Java 타입이 해당 함수의 선언 인자 타입과 호환되지 않으면 감지한다. (예: `ABS({0})`에 `StringPath` 바인딩) |
| D-02 | **오류 2 감지**: 템플릿 문자열에 Hibernate 미등록 네이티브 함수(예: `DATE_FORMAT`)가 직접 호출 형태로 사용되고, 그 템플릿 표현식이 `where`/`having`에서 타입이 있는 표현식(엔티티 컬럼 등)과 비교 연산으로 결합되면 감지한다. |
| D-03 | D-02는 비교 연산의 **좌변/우변 어느 쪽에 템플릿이 위치하든** 감지해야 한다. |
| D-04 | D-02는 함수 인자가 파라미터 바인딩(`{1}`)이든 템플릿 내 리터럴(`'%Y%m%d'`)이든 동일하게 감지해야 한다. |

### 3.3 감지 제외(우회 패턴) 요구사항

이미 우회 조치가 적용된 코드는 오탐을 막기 위해 감지에서 제외한다.

| ID | 요구사항 |
|---|---|
| E-01 | **오류 1 우회 — `cast` 적용 패턴 제외**: 인자에 `cast`가 적용되어 타입이 명시적으로 변환된 경우 감지하지 않는다. 예: `Expressions.numberTemplate(Long.class, "ABS(cast({0} as long))", qEntity.stringColumn)` |
| E-02 | **오류 2 우회 — `function(...)` 적용 패턴 제외**: JPQL 표준 함수 호출 구문 `function('함수명', ...)` 형태로 작성된 경우 감지하지 않는다. 예: `Expressions.stringTemplate("function('DATE_FORMAT', {0}, {1})", qEntity.column, "%Y%m%d")` |

---

## 4. 산출물

1. **분석 문서**
   - QueryDSL `Expressions.template(..)` 계열 메서드 목록 및 오류 1/오류 2 발생 가능성 분류표 (R-01, R-02)
   - Hibernate 6.6.49.Final JPQL 함수 목록(파라미터/반환 타입 포함) 및 오류 발생 가능성 분류표 (R-03, R-04)
2. **패턴 감지 계획서**: 위 분류표를 근거로 한 감지 규칙(D-01~D-04)과 제외 규칙(E-01~E-02)의 구체적 구현 계획
3. **Maven 플러그인 구현**: 빌드 시점에 소스를 정적 분석하여 위험 패턴을 보고하는 플러그인

---

## 5. 범위 외 (Out of Scope)

- 감지된 코드의 자동 수정(auto-fix)
- QueryDSL Template 이외의 경로로 작성된 네이티브 쿼리(`@Query(nativeQuery = true)` 등) 분석
- Hibernate 6.6.49.Final 이외 버전에 대한 호환성 검증
