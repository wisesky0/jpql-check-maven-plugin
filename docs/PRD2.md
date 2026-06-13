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
    .from(qHmCmccProdCstcRellEntity, qHmCmccProdCstcRellEntity02)   // FROM 다중 루트 (첫 번째/마지막)
    .innerJoin(qHmCmccLoblEntity)
        .on(qHmCmccLoblEntity.loblCd
            .eq(qHmCmccProdCstcRellEntity.trgtProdItemCdv))         // ★★ 오류 지점: 첫 번째 루트 참조
    .innerJoin(qHmCmccSvcEntity)
        .on(qHmCmccSvcEntity.svcCd.eq(qHmCmccLoblEntity.svcCd))     // 정상: 같은 트리의 형제 JOIN 참조
    .innerJoin(qHmCmccRatSvcFctrRellEntity)
        .on(qHmCmccRatSvcFctrRellEntity.ratCd
            .eq(qHmCmccProdCstcRellEntity02.trgtProdItemCdv))       // 정상: 마지막 루트(02) 참조
    .innerJoin(qHmCmccProdEntity)
        .on(qHmCmccProdEntity.prodCd
            .eq(qHmCmccProdCstcRellEntity02.baseProdItemCdv))       // 정상: 마지막 루트(02) 참조
    .where(qHmCmccProdCstcRellEntity.baseProdItemCdv.eq(prodCd)     // 정상: WHERE는 범위 제한 없음
        ...)
    .fetch();
```

직렬화된 JPQL 구조 (개념도):

```
from HmCmccProdCstcRellEntity e,        ← 루트 트리 1 (JOIN 없음, 고립)
     HmCmccProdCstcRellEntity e02       ← 루트 트리 2 — 모든 명시적 JOIN이 이 트리에 소속
       inner join HmCmccLoblEntity l        with l.loblCd = e.trgtProdItemCdv   ← ★ 트리 1 침범 → 예외
       inner join HmCmccSvcEntity s         with s.svcCd  = l.svcCd             ← 같은 트리 → 정상
       inner join HmCmccRatSvcFctrRell r    with r.ratCd  = e02.trgt...         ← 자기 루트 → 정상
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

### 2.2 핵심 원칙 — Hibernate 소스 기반 검증 메커니즘

Hibernate 6은 **SQM(Semantic Query Model)** 기반으로 동작하며, FROM 절을 **루트별 트리의 모음(forest)** 으로 모델링한다.

#### (1) JOIN이 어느 루트에 속하는지의 기준 (구문적 소속)

HQL 문법상 FROM 절은 `entityWithJoins (, entityWithJoins)*` 구조이며,
`entityWithJoins = fromRoot join*` 이다. 즉 **쉼표로 구분된 각 루트가 자신의 JOIN 그룹을 소유**하고,
명시적 JOIN은 **직렬화된 JPQL에서 자신이 뒤따르는(가장 가까운 앞의) FROM 루트**에 붙는다.

- 근거: `SemanticQueryBuilder.visitEntityWithJoins()` → `consumeJoin(ctx, sqmRoot)` → `sqmRoot.addSqmJoin(join)`
- QueryDSL `.from(A, B).innerJoin(C)...` 는 `from A a, B b inner join C c with ...` 로 직렬화되므로,
  뒤따르는 명시적 entity join들은 모두 **마지막 FROM 루트(B)의 트리**에 속한다.
- 단, `.from(a)` 호출과 `.innerJoin(...)` 호출 순서가 섞이면(예: `.from(a).innerJoin(c)....from(b)`)
  직렬화 순서가 달라져 JOIN이 **앞쪽 루트**에 붙을 수 있다. QueryDSL의 join 목록은 **호출 순서대로** 직렬화된다.

#### (2) ON 절 검증 규칙 (`QualifiedJoinPredicatePathConsumer.validateAsRoot`)

ON(`with`) 절의 predicate를 파싱할 때, predicate에 나타나는 **모든 경로**에 대해:

```java
final SqmRoot<?> root = pathRoot.findRoot();        // 참조 경로가 속한 루트
final SqmRoot<?> joinRoot = sqmJoin.findRoot();     // 현재 JOIN이 속한 루트
if ( root != joinRoot ) {
    // (서브쿼리/상관관계 예외 처리 후)
    throw new SemanticException(
        "SqmQualifiedJoin predicate referred to SqmRoot [%s] other than the join's root [%s]" ...);
}
```

즉 **판정 기준은 "JOIN 대상 엔티티와 같은가"가 아니라 "같은 루트 트리(findRoot)에 속하는가"** 이다.

- ON 절에서 참조 가능한 범위: JOIN 대상 자신 + JOIN이 속한 루트 + **같은 루트 트리에 이미 붙은 다른 JOIN들**
- 참조 불가: **다른 FROM 루트**(및 그 트리에 속한 경로)
- 예외 허용: 서브쿼리의 상관(correlation) 참조, ON 절 내부 서브쿼리에서 상위 쿼리 루트 참조

### 2.3 문제 패턴 분류

#### 문제 패턴 — FROM 다중 루트 + JOIN ON에서 "JOIN이 속하지 않은 루트" 참조

```java
// 문제 구조
.from(rootA, rootB)           // rootA, rootB 모두 FROM 루트
.innerJoin(entityC)           // 직렬화상 entityC JOIN은 마지막 루트 rootB의 트리에 소속
    .on(entityC.field.eq(rootA.field))  // ★ rootA는 entityC JOIN이 속한 트리(rootB) 밖 → 예외
```

- `entityC.field.eq(rootB.field)` 처럼 **자기 트리의 루트(rootB)** 를 참조하는 것은 **정상**
- `rootA`(JOIN이 속하지 않은 다른 FROM 루트)를 참조하는 순간 `SemanticException` 발생
- Hibernate 5는 이 검증이 없어 허용되었음 → 마이그레이션 후 런타임에서 처음 드러남

#### 정상 패턴 — 같은 루트 트리 내부 참조

```java
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.key.eq(rootB.key))    // 정상: rootB = JOIN이 속한 루트
.innerJoin(entityD).on(entityD.key.eq(entityC.key))  // 정상: entityC는 같은 트리의 형제 JOIN
                                                      // (findRoot()가 모두 rootB로 동일)
```

연쇄 JOIN 간 상호 참조는 `findRoot()`가 같은 루트로 귀결되므로 합법이다. 문제는 오직
**다른 FROM 루트 트리를 넘나드는(cross-tree) 참조**다.

---

## 3. 감지 대상 오류 정의

### 오류 3 (규칙 D-10): JOIN ON 절 교차 루트 참조

**정의**: QueryDSL `.innerJoin(entity).on(predicate)` 또는 `.leftJoin(entity).on(predicate)` 호출에서,  
ON 절의 predicate가 **해당 JOIN이 속한 루트 트리 밖의 다른 FROM 루트 Path**를 참조하는 경우.

**조건**:
1. `.from(rootA, rootB, ...)` 형태로 다중 루트가 선언되어 있음 (루트 1개면 위반 불가능)
2. `.innerJoin(entityX).on(...)` ON 절 predicate 내에 `rootN.field` 형태의 경로 참조가 존재함
3. `rootN`이 **해당 JOIN이 소속된 루트가 아닌** 다른 FROM 루트임
   - JOIN 소속 루트 판정: 직렬화된 JPQL에서 JOIN이 뒤따르는 from 요소.
     QueryDSL의 join 목록은 호출 순서대로 직렬화되므로, 일반적인
     `.from(rootA, rootB).innerJoin(...)` 체인에서는 **마지막 FROM 루트(rootB)** 가 모든 명시적 JOIN의 소속 루트가 됨
   - 같은 트리의 형제 JOIN 참조(`findRoot()` 동일)는 위반 아님

**런타임 예외**: `org.hibernate.query.SemanticException: SqmQualified Join predicate referred to SomRoot [...] other than the join's root [...]`

---

## 4. 감지 제외 (정상 패턴)

### E-10: FROM 단일 루트 + JOIN ON

```java
// FROM 루트가 하나이면 ON 절에서 FROM 루트 참조는 허용됨
.from(rootA)
.innerJoin(entityB).on(entityB.key.eq(rootA.key))   // 정상
```

### E-11: JOIN 소속 루트(마지막 FROM 루트) 또는 형제 JOIN 참조

```java
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.eq(rootB.field))  // 정상 — rootB = JOIN 소속 루트
.innerJoin(entityD).on(entityD.field.eq(entityC.field)) // 정상 — 같은 트리의 형제 JOIN
```

### E-12: WHERE 절로 이동된 교차 조건

```java
// 교차 루트 조건은 WHERE에서는 제한 없음
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.eq(rootB.field))
.where(entityC.key.eq(rootA.key))                      // 정상 — WHERE는 범위 제한 없음
```

---

## 5. 우회(수정) 방법

### 수정 방법 1: 교차 조건을 WHERE 절로 이동

```java
// 수정 전 (오류 발생 — rootA는 JOIN 소속 루트가 아님)
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.eq(rootA.field))

// 수정 후
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.eq(rootB.field))  // ON에는 자기 트리 조건만
.where(entityC.field2.eq(rootA.field))                 // 교차 조건은 WHERE로
```

### 수정 방법 2: FROM 다중 루트 제거 → 명시적 JOIN 체인으로 변경

```java
// 수정 전 (오류 발생)
.from(rootA, rootB)
.innerJoin(entityC).on(entityC.field.eq(rootA.field))

// 수정 후 — rootA를 FROM에서 제거하고 JOIN으로 흡수 (단일 트리화)
.from(rootB)
.innerJoin(rootA).on(rootA.key.eq(rootB.key))          // rootA를 명시적 JOIN으로
.innerJoin(entityC).on(entityC.field.eq(rootA.field))  // 이제 rootA는 같은 트리 → 합법
```

> **권장**: 수정 방법 2는 쿼리 의미를 명확히 하고 불필요한 카테시안 곱을 방지하므로 우선 고려한다.

### 수정 방법 3: 참조 루트가 단순 오타인 경우 — 의도한 루트로 교정

self-join 용도로 같은 엔티티의 Q 인스턴스를 2개(`q`, `q02`) 선언한 경우,
ON 절에서 `q`와 `q02`를 혼동한 단순 오타가 원인일 수 있다. 의미상 마지막 루트를
참조해야 하는 자리라면 해당 루트로 교정하는 것이 가장 단순한 수정이다.

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

**Pass B — JOIN ON 절 교차 루트 참조 검사**

```
# 체인 분석 후:
attachedRoot = fromRoots.last()            # QueryDSL 직렬화상 명시적 JOIN들이 붙는 루트
joinTargets  = {innerJoin/leftJoin/... 의 args[0] 심볼들}  # 같은 트리의 형제 JOIN 집합

visitMethodInvocation:
  if methodName == "on":
    predicate = args[0]
    referencedRootSymbols = collectPathRootSymbols(predicate)
    for sym in referencedRootSymbols:
      if sym in fromRoots AND sym != attachedRoot:
        report(D-10, ...)        # 다른 FROM 루트 트리 침범
      # sym이 joinTargets 또는 attachedRoot이면 합법 (같은 트리)
```

> 주의: `.from(a).innerJoin(c)....from(b)` 처럼 from/join 호출 순서가 섞이면
> attachedRoot가 달라질 수 있다. 1차 구현에서는 join 목록의 호출 순서를 추적하여
> 각 JOIN 직전의 마지막 from 요소를 attachedRoot로 판정한다.

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
[D-10] MyRepository.java:[35,8] JOIN ON 절이 해당 JOIN이 속한 루트 트리 밖의
       FROM 루트(qHmCmccProdCstcRellEntity)를 참조합니다.
  JOIN 대상     : qHmCmccLoblEntity (HmCmccLoblEntity)
  JOIN 소속 루트 : qHmCmccProdCstcRellEntity02 (마지막 FROM 루트)
  ON 절 위반 참조: qHmCmccProdCstcRellEntity.trgtProdItemCdv (첫 번째 FROM 루트)
  FROM 루트 목록 : qHmCmccProdCstcRellEntity, qHmCmccProdCstcRellEntity02
  권장 조치     : 의도한 루트(02)로 교정하거나, 교차 조건을 WHERE 절로 이동하거나,
                  FROM 다중 루트를 JOIN 체인으로 재구성
```

---

## 8. 수용 기준 (Acceptance Criteria)

| TC | 설명 | 기대 결과 |
|---|---|---|
| TC2-01 | `.from(A, B).innerJoin(C).on(C.x.eq(A.y))` — A는 첫 번째 루트 (JOIN은 마지막 루트 B 소속) | D-10 감지 |
| TC2-02 | `.from(A).innerJoin(B).on(B.x.eq(A.y))` — FROM 루트 1개 | 정상 (감지 없음) |
| TC2-03 | `.from(A, B).innerJoin(C).on(C.x.eq(B.y))` — B = JOIN 소속 루트(마지막) | 정상 (감지 없음) |
| TC2-04 | `.from(A, B).innerJoin(C).on(C.x.eq(B.y)).innerJoin(D).on(D.x.eq(C.y))` — 형제 JOIN 참조 | 정상 (감지 없음) |
| TC2-05 | `.from(A, B).innerJoin(C).on(C.x.eq(B.y)).where(C.k.eq(A.k))` — 교차 조건이 WHERE에 있는 경우 | 정상 (감지 없음) |
| TC2-06 | `.from(A, B).innerJoin(C).on(C.x.eq(A.y)).where(...)` — 혼합 패턴 | D-10 감지 (ON 절만) |
| TC2-07 | 변수 경유: `BooleanExpression cond = C.x.eq(A.y); .from(A, B).innerJoin(C).on(cond)` | D-10 감지 (가능한 경우) |

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
