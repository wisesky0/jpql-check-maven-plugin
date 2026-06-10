# PRD2 — QueryDSL JOIN ON 절 교차 루트 참조 감지

**버전**: 1.0  
**작성일**: 2026-06-10  
**연관 파일**: docs/assets/qquery-error.md

---

## 1. 배경 및 목적

### 1.1 오류 원문

```
Caused by: org.hibernate.query.SemanticException: SqmQualified Join predicate
referred to SomRoot [HmCmccProdCstcRellEntity(hmCmccProdCstcRellEntity)]
other than the join's root [HmCmccLoblEntity(hmCmccLoblEntity)]
```

### 1.2 오류가 발생한 QueryDSL 코드

```java
queryFactory
    .select(...).distinct()
    .from(qHmCmccProdCstcRellEntity, qHmCmccProdCstcRellEntity02)          // FROM 다중 루트
    .innerJoin(qHmCmccLoblEntity)
        .on(qHmCmccLoblEntity.loblCd
            .eq(qHmCmccProdCstcRellEntity02.trgtProdItemCdv))              // ★ FROM 루트 참조
    .innerJoin(qHmCmccRatSvcFctrRellEntity)
        .on(qHmCmccRatSvcFctrRellEntity.ratCd
            .eq(qHmCmccProdCstcRellEntity02.trgtProdItemCdv))              // ★ FROM 루트 참조
    .innerJoin(qHmCmccProdEntity)
        .on(qHmCmccProdEntity.prodCd
            .eq(qHmCmccProdCstcRellEntity02.baseProdItemCdv))              // ★ FROM 루트 참조
    ...
```

---

## 2. 근본 원인 분석

### 2.1 Hibernate 5 vs Hibernate 6 동작 차이

| 항목 | Hibernate 5 (HQL/JPQL) | Hibernate 6 (SQM) |
|---|---|---|
| JOIN ON 절 범위 | 쿼리 전체 범위의 모든 루트/조인 경로 참조 허용 | **해당 JOIN 자신의 루트만** 참조 허용 |
| 검증 위치 | SQL 생성 시 느슨하게 처리 | SqmQualifiedJoin 시맨틱 분석 단계에서 엄격 검증 |
| 예외 클래스 | 발생 안 함 (정상 SQL 생성) | `org.hibernate.query.SemanticException` |

### 2.2 핵심 원칙

Hibernate 6은 **SQM(Semantic Query Model)** 기반으로 동작하며, JPA 2.2 스펙의 엄격한 해석을 따른다.

> JPA 스펙 §4.4.5.2: "The ON condition is evaluated for each combination of the join's joined entity and the FROM clause roots identified by the correlation expression."

Hibernate 6의 `TypecheckUtil` 및 `SqmQualifiedJoin` 내부 검증 로직은:

1. `innerJoin(entity).on(predicate)` 호출 시 **predicate가 참조하는 루트(SqmRoot)**를 추출한다.
2. predicate 내 참조된 루트가 **해당 JOIN의 대상 루트와 다른 FROM 루트**인 경우 `SemanticException`을 발생시킨다.

### 2.3 문제 패턴 분류

#### 패턴 A — FROM 다중 루트 + JOIN ON에서 비-조인 루트 참조

```java
// 문제 구조
.from(rootA, rootB)           // rootA, rootB 모두 FROM 루트
.innerJoin(entityC)
    .on(entityC.field.eq(rootB.field))  // ← rootB는 현재 JOIN(entityC)의 루트가 아님
```

- `entityC` JOIN의 ON 절에 `rootB` (FROM 루트) 경로가 나타남
- Hibernate 6: `SemanticException` — predicate가 join의 루트가 아닌 다른 루트 참조

#### 패턴 B — 연쇄 JOIN에서 이전 JOIN 루트를 ON에 참조 (관련 가능성)

```java
.from(rootA)
.innerJoin(entityB).on(entityB.key.eq(rootA.key))    // 정상: rootA는 FROM 루트
.innerJoin(entityC).on(entityC.key.eq(entityB.key))  // 정상: entityB는 직전 JOIN 대상
.innerJoin(entityD).on(entityD.key.eq(entityB.key))  // 위험: entityB가 FROM 루트가 아님
                                                      // Hibernate 5에선 허용, 6에선 검증 필요
```

> **주의**: 패턴 B는 Hibernate 6에서도 허용될 수 있음(연속 JOIN 체인은 SQM 그래프에서 연결됨). 본 PRD는 **확인된 패턴 A에 집중**하며, 패턴 B는 향후 검증 대상으로 분류한다.

---

## 3. 감지 대상 오류 정의

### 오류 3 (규칙 D-10): JOIN ON 절 교차 루트 참조

**정의**: QueryDSL `.innerJoin(entity).on(predicate)` 또는 `.leftJoin(entity).on(predicate)` 호출에서,  
ON 절의 predicate 인자가 **해당 JOIN의 대상 엔티티와 무관한 FROM 루트의 Path**를 참조하는 경우.

**조건**:
1. `.from(rootA, rootB, ...)` 형태로 다중 루트가 선언되어 있음
2. `.innerJoin(entityX).on(...)` ON 절 predicate 내에 `rootN.field` 형태의 경로 참조가 존재함
3. `rootN`이 `entityX`와 다른 FROM 루트임

**런타임 예외**: `org.hibernate.query.SemanticException: SqmQualified Join predicate referred to SomRoot [...] other than the join's root [...]`

---

## 4. 감지 제외 (정상 패턴)

### E-10: FROM 단일 루트 + JOIN ON

```java
// FROM 루트가 하나이면 ON 절에서 FROM 루트 참조는 허용됨
.from(rootA)
.innerJoin(entityB).on(entityB.key.eq(rootA.key))   // 정상
```

### E-11: WHERE 절로 이동된 조건

```java
// ON 절에는 join 자체 조건만, 교차 조건은 WHERE로
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.eq(rootA.field))  // 정상 — rootA가 1번 FROM 루트
.where(entityC.key.eq(rootB.key))                      // 정상 — WHERE는 범위 제한 없음
```

---

## 5. 우회(수정) 방법

### 수정 방법 1: 교차 조건을 WHERE 절로 이동

```java
// 수정 전 (오류 발생)
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.eq(rootB.field))

// 수정 후
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.isNotNull())  // JOIN 자체 조건만 ON에
.where(entityC.field.eq(rootB.field))              // 교차 조건은 WHERE로
```

### 수정 방법 2: FROM 다중 루트 제거 → 명시적 JOIN 체인으로 변경

```java
// 수정 전 (오류 발생)
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.eq(rootB.field))

// 수정 후 — rootB를 FROM에서 제거하고 JOIN으로 흡수
.from(rootA)
.innerJoin(rootB).on(rootB.key.eq(rootA.key))          // rootB를 명시적 JOIN으로
.innerJoin(entityC).on(entityC.field.eq(rootB.field))  // 이제 rootB는 JOIN 체인상 연결됨
```

> **권장**: 수정 방법 2는 쿼리 의미를 명확히 하고 불필요한 카테시안 곱을 방지하므로 우선 고려한다.

---

## 6. 판정 알고리즘 (정적 분석 설계)

### 6.1 분석 대상

QueryDSL `JPAQuery` / `JPAQueryFactory` 메서드 체인에서:

- `.from(arg1, arg2, ...)` — 다중 인수면 다중 루트 선언으로 기록
- `.innerJoin(target).on(predicate)` / `.leftJoin(target).on(predicate)` — ON 절 분석 대상

### 6.2 Pass 구조

**Pass A — FROM 루트 수집**

```
visitMethodInvocation:
  if methodName == "from":
    args.filter(type는 EntityPath 하위).forEach:
      fromRoots.add(argSymbol)
```

**Pass B — JOIN ON 절 교차 참조 검사**

```
visitMethodInvocation:
  if methodName in {"innerJoin", "leftJoin", "join", "rightJoin"}:
    joinTarget = args[0]                   # JOIN 대상 엔티티
    joinRoot = resolveSymbol(joinTarget)

  if methodName == "on":
    predicate = args[0]
    referencedPaths = collectPathSymbols(predicate)
    for path in referencedPaths:
      if path in fromRoots AND path != joinRoot:
        report(D-10, ...)
```

### 6.3 복잡성 고려사항

| 고려사항 | 내용 |
|---|---|
| 메서드 체인 추적 | QueryDSL은 유창한(fluent) API이므로 체인 전체를 역추적해야 함 |
| 변수 경유 | `BooleanExpression cond = rootB.field.eq(...); .on(cond)` 형태 처리 필요 |
| 중첩 서브쿼리 | 서브쿼리 내 FROM/JOIN은 별도 스코프로 처리 |
| 타입 확인 | joinTarget이 `EntityPath<?>` 하위 타입인지 확인 필요 |

---

## 7. Finding 출력 형식

```
[D-10] MyRepository.java:[35,8] JOIN ON 절이 해당 JOIN의 대상 엔티티가 아닌
       FROM 루트(qHmCmccProdCstcRellEntity02)를 참조합니다.
  JOIN 대상  : qHmCmccLoblEntity (HmCmccLoblEntity)
  ON 절 참조 : qHmCmccProdCstcRellEntity02.trgtProdItemCdv
  FROM 루트  : qHmCmccProdCstcRellEntity, qHmCmccProdCstcRellEntity02
  권장 조치  : 교차 조건을 WHERE 절로 이동하거나 FROM 다중 루트를 JOIN 체인으로 재구성
```

---

## 8. 수용 기준 (Acceptance Criteria)

| TC | 설명 | 기대 결과 |
|---|---|---|
| TC2-01 | `.from(A, B).innerJoin(C).on(C.x.eq(B.y))` — B는 FROM 루트, C가 JOIN 대상 | D-10 감지 |
| TC2-02 | `.from(A).innerJoin(B).on(B.x.eq(A.y))` — FROM 루트 1개 | 정상 (감지 없음) |
| TC2-03 | `.from(A, B).innerJoin(C).on(C.x.eq(A.y))` — A는 첫 번째 FROM 루트이므로 JOIN 대상과 무관 | D-10 감지 |
| TC2-04 | `.from(A, B).where(C.x.eq(B.y))` — 교차 조건이 WHERE에 있는 경우 | 정상 (감지 없음) |
| TC2-05 | `.from(A, B).innerJoin(C).on(C.x.eq(B.y)).where(...)` — 혼합 패턴 | D-10 감지 (ON 절만) |
| TC2-06 | 변수 경유: `BooleanExpression cond = C.x.eq(B.y); .innerJoin(C).on(cond)` | D-10 감지 (가능한 경우) |

---

## 9. 구현 우선순위 및 범위

### 현재 PRD1 (docs/PRD.md) 와의 관계

| 구분 | PRD1 (오류 1, 2) | PRD2 (오류 3) |
|---|---|---|
| 오류 계층 | 컴파일 가능, 런타임 타입 불일치 | 컴파일 가능, 런타임 시맨틱 예외 |
| 예외 클래스 | `TypecheckUtil` 관련 예외 | `org.hibernate.query.SemanticException` |
| 분석 대상 | `Expressions.*Template(..)` 인수 타입 | QueryDSL 체인의 FROM/JOIN 구조 |
| 감지 난이도 | 중 (템플릿 문자열 파싱 + 타입 추론) | 고 (체인 전체 역추적 + 스코프 관리) |

### 구현 난이도 평가

QueryDSL의 유창한 API 체인을 Compiler Tree API로 역추적하는 것은 기술적으로 복잡하다:

- `.from()`, `.innerJoin()`, `.on()` 각각이 별개의 메서드 호출 노드로 분리됨
- 체인 전체를 재조립하여 FROM 루트 목록과 JOIN-ON 쌍을 연결해야 함
- 변수 경유 패턴(D-08 유사)도 처리 필요

**권장 구현 순서**:
1. 단순 체인 내 직접 참조(TC2-01~TC2-05) 우선 구현
2. 변수 경유 패턴(TC2-06)은 2차 구현

---

## 10. 용어 정의

| 용어 | 정의 |
|---|---|
| FROM 루트 | `.from(entity)` 또는 `.from(a, b, ...)` 에 나열된 각 엔티티. SQM에서 `SqmRoot`로 표현 |
| JOIN 루트 | `.innerJoin(entity)`의 대상 엔티티. SQM에서 `SqmQualifiedJoin`의 lhs |
| ON 절 predicate | `.on(booleanExpression)`에 전달되는 조건식 |
| 교차 참조 | ON 절에서 현재 JOIN 루트가 아닌 다른 FROM 루트의 경로를 참조하는 것 |
| SQM | Semantic Query Model — Hibernate 6의 HQL/JPQL 내부 표현 모델 |
