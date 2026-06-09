
# JPQL 함수 인자 타입 불일치 정적 탐지 도구 요구사항 명세서

> **버전**: 2.0 (Maven 플러그인 배포 모델 + 진단 정보 확장 + 리포트 출력 반영)

## 1. 개요 (Overview)

### 1.1 목적
QueryDSL Q-Entity의 컬럼 경로(`Path<T>`)를 JPQL 표준/Hibernate 확장 함수의 인자로 전달할 때 발생하는 **타입 불일치를 컴파일 타임에 정적으로 탐지**하여, 런타임 `QueryException` / `SemanticException`을 사전에 차단한다.

### 1.2 확정된 설계 전제
| 항목 | 결정 | 근거 |
|------|------|------|
| 동작 시점 | 컴파일 타임 정적 분석 (APT + Compiler Tree API) | 런타임 도달 전 차단, CI 게이트 적용 가능 |
| 타입 규칙 기준 | Hibernate 6 `FunctionReturnTypeResolver` / `ArgumentsValidator` 실제 동작 | 실제 런타임 예외와 판정 결과 일치 필요 |
| 검사 함수 범위 | JPA 표준(JSR 338) + Hibernate 확장(`CommonFunctionFactory` 등록분) | Hibernate 6 환경에서 실제 사용 가능한 전체 함수 |
| **타입 해석 엔진** | **Annotation Processor (javac 내부)** | Maven 플러그인은 컴파일러 타입 정보 직접 접근 불가 |
| **배포/사용 모델** | **Maven 플러그인으로 래핑** | 사용자 요구 — pom 한 곳 설정, 라이프사이클 통합 |

### 1.3 아키텍처 핵심 결정: 2-계층 구조
타입 해석은 컴파일 파이프라인 안에서만 가능하므로, 도구는 **단일 산출물이 아니라 두 모듈의 결합**으로 구성한다.

| 계층 | 산출물 | 역할 |
|------|--------|------|
| **분석 코어** | Annotation Processor JAR | 실제 AST 순회·타입 해석·불일치 판정 (FR-1~FR-4) |
| **사용성 래퍼** | **Maven 플러그인 (Mojo)** | 코어 처리기를 컴파일에 자동 주입, 설정/리포트/게이트 제어 (FR-6) |

> 사용자는 Maven 플러그인 하나만 `pom.xml`에 선언하면 되고, 플러그인이 내부적으로 `maven-compiler-plugin`의 `annotationProcessorPaths`에 분석 코어를 등록·정렬한다. (직접 수동 등록 방식도 지원 — FR-6.3)

### 1.4 해결하려는 문제
QueryDSL은 `NumberExpression`, `StringExpression` 등 자체 표현식 타입 체계를 가지지만, `Expressions.numberOperation()` 이나 커스텀 함수 호출 시 **인자의 실제 도메인 타입(예: `Path<String>`)이 함수가 요구하는 타입(예: 숫자)과 맞는지 컴파일러가 검증하지 못한다.** 결과적으로 `abs(stringColumn)` 같은 코드가 컴파일을 통과하고 런타임에 깨진다.

---

## 2. 기능 요구사항 (Functional Requirements)

### FR-1. Q-Entity 컬럼 경로 인식
- **FR-1.1** 처리기는 컴파일 단위에서 QueryDSL이 생성한 Q-클래스(`QXxx`) 필드 참조를 식별한다.
- **FR-1.2** 각 컬럼 경로의 정적 타입을 QueryDSL 표현식 타입(`StringPath`, `NumberPath<BigDecimal>`, `DateTimePath<Instant>` 등)에서 **Java 도메인 타입**으로 정규화한다. (예: `NumberPath<BigDecimal>` → `BigDecimal`)
- **FR-1.3** 정규화 실패(제네릭 소거, 와일드카드 `?`, 동적 `Expression<?>`) 시 해당 인자를 **UNRESOLVED**로 표시하고 경고만 발생시킨다(오탐 방지).

### FR-2. 함수 호출 지점 탐지
- **FR-2.1** 다음 패턴의 JPQL 함수 호출을 탐지한다.
  - QueryDSL 빌트인 메서드: `.abs()`, `.sqrt()`, `.substring()`, `.length()`, `.coalesce()` 등
  - `Expressions.*Operation(...)` 직접 호출
  - 커스텀 `template()` / `FUNCTION()` 매핑 호출
- **FR-2.2** 각 호출에서 (a) 함수 식별자, (b) 인자 순서, (c) 각 인자의 정규화된 타입을 추출한다.

### FR-3. 함수 시그니처 카탈로그
- **FR-3.1** Hibernate 6 기준 함수 카탈로그를 내장한다. 각 엔트리: `name`, `category`(STRING/NUMERIC/DATETIME/GENERAL/AGGREGATE), `argumentsValidator`, `returnTypeResolver`, `source`(JPA_STANDARD/HIBERNATE_EXTENSION), `minJpaVersion`.
- **FR-3.2** 카탈로그는 외부 설정 파일(`function-catalog.yaml`)로 오버라이드 가능(프로젝트별 커스텀 함수 대응).

### FR-4. 타입 불일치 판정 (핵심)
- **FR-4.1** 판정 로직은 Hibernate 6의 두 단계 검증을 모사한다. ① `ArgumentsValidator`(개수+타입 카테고리), ② `FunctionReturnTypeResolver`(반환 타입 추론).
- **FR-4.2** **ERROR**: 함수 요구 타입 카테고리와 인자 타입 카테고리 비호환(예: `abs(String)`), 인자 개수 불일치(예: `mod(x)`).
- **FR-4.3** **WARNING**: 정밀도/의도 손실 가능 암묵 변환, UNRESOLVED 인자, 현재 JPA/Hibernate 버전보다 상위 버전 전용 함수.

### FR-5. 진단 정보 및 출력 (이번 업데이트 확장)

#### FR-5.1 진단에 포함하는 정보 (확장)
각 검출 결과는 다음을 **모두** 포함한다.

| 항목 | 추출 API | 예시 |
|------|----------|------|
| 소스 파일명 | `Trees → CompilationUnitTree → getSourceFile().getName()` | `UserService.java` |
| 라인·컬럼 위치 | `SourcePositions` + `LineMap` | `[47, 32]` |
| 함수명 + 카테고리 | `MethodInvocationTree` + 카탈로그 | `abs (NUMERIC)` |
| **엔티티 클래스** | Q-클래스 역매핑 (FR-5.4) | `User` |
| **컬럼(필드)명** | Path 식별자 / PathMetadata | `name` |
| 인자 인덱스 | 호출 인자 리스트 순번 | `0` |
| 기대 타입 | 함수 카탈로그 | `Number 계열` |
| 실제 타입 | 인자 `TypeMirror` | `String` |
| 판정 근거 | 위배된 Validator/Resolver 규칙 | `ArgumentsValidator: 숫자 인자 요구` |

#### FR-5.2 진단 메시지 표준 포맷
```
[ERROR] UserService.java:[47,32] JPQL 함수 타입 불일치
  함수       : abs (NUMERIC 카테고리)
  대상       : User.name (String)         ← 엔티티.컬럼 (실제 타입)
  인자 인덱스 : 0
  기대 타입   : Number 계열
  판정 근거   : ArgumentsValidator - abs는 숫자 인자 요구, String 비호환
```

#### FR-5.3 출력 채널 (2종, 용도 분리)
| 채널 | 구현 | 출력 위치 | 용도 |
|------|------|----------|------|
| ① 컴파일러 진단 | `Messager.printMessage(Kind, msg, element)` | 빌드 로그 / IDE Problems 창 | 즉각 피드백, IDE 라인 점프, 빌드 게이트 |
| ② 파일 리포트 | `processingOver()` 라운드에서 파일 IO | `target/jpql-check/` | CI 아티팩트, 팀 공유, 거버넌스 연동 |

- **FR-5.3.1** 채널 ①은 항상 활성. `Kind.ERROR`이면 컴파일 실패로 **빌드 자동 차단**.
- **FR-5.3.2** 채널 ② 파일 형식은 설정 가능: `json`(후속 자동화), `html`(사람 열람), `sarif`(GitHub Code Scanning / SonarQube 연동). 다중 동시 출력 허용.
- **FR-5.3.3** 리포트 출력 경로 기본값 `target/jpql-check/` — `mvn clean` 시 함께 정리됨. 설정으로 변경 가능.

#### FR-5.4 엔티티/컬럼 역매핑 전략
```
방법 A (기본): Q-클래스 명명 규칙 역산  QUser → User, qUser.name → "name"
방법 B (폴백): PathMetadata에서 root 타입·property 정적 추출 (비표준 prefix 대응)
```
- **FR-5.4.1** 기본 A, 실패 시 B 폴백. 둘 다 실패 시 컬럼명을 식별자 원문으로 표기하고 WARNING.

### FR-6. Maven 플러그인 요구사항 (신규)

#### FR-6.1 플러그인 goal
- **FR-6.1.1** `check` goal 제공 — 분석 코어 처리기를 컴파일에 주입하고 검사를 실행한다.
- **FR-6.1.2** 기본 바인딩 라이프사이클 단계: `process-classes` 직후 또는 `compile` 단계와 연동(분석 코어가 컴파일과 함께 도므로, 플러그인은 등록·설정·리포트 집계·게이트 판정을 담당).

#### FR-6.2 플러그인 설정 파라미터
| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `failOnError` | `true` | ERROR 검출 시 빌드 실패 여부 (FR-5.3 게이트) |
| `failOnWarning` | `false` | WARNING도 실패 처리할지 |
| `reportFormats` | `[json]` | `json`/`html`/`sarif` 다중 지정 |
| `reportDirectory` | `${project.build.directory}/jpql-check` | 리포트 출력 디렉터리 |
| `functionCatalog` | (내장) | 외부 카탈로그 YAML 경로 (FR-3.2) |
| `processorOrder` | `after-querydsl` | Q-클래스 생성기 다음 배치 보장 |
| `suppressUnresolved` | `false` | UNRESOLVED WARNING 노이즈 억제 |

#### FR-6.3 처리기 주입 방식
- **FR-6.3.1 (자동, 권장)** 플러그인이 `maven-compiler-plugin`의 `annotationProcessorPaths`에 분석 코어를 **QueryDSL 처리기 다음 순서로** 자동 등록한다.
- **FR-6.3.2 (수동 대안)** 자동 주입이 불가한 빌드 구성에서는, 사용자가 직접 `annotationProcessorPaths`에 분석 코어 JAR을 등록하는 가이드를 제공한다.

#### FR-6.4 라운드 순서 보장 (§8-1 리스크 대응)
- **FR-6.4.1** 분석 코어 처리기는 **`processingOver()`가 true인 마지막 라운드**에서만 실제 검사를 수행한다. QueryDSL이 모든 Q-클래스를 생성 완료한 시점을 보장하기 위함.
- **FR-6.4.2** 처리기는 `return false`로 애너테이션을 소비하지 않아 다른 처리기 동작을 방해하지 않는다.

#### FR-6.5 폐쇄망 배포 (NFR-5 연계)
- **FR-6.5.1** 플러그인 + 분석 코어 JAR을 사내 Nexus/Artifactory에 배포. 외부 네트워크 의존 없음.

---

## 3. 비기능 요구사항 (Non-Functional Requirements)

| ID | 항목 | 요구 내용 |
|----|------|-----------|
| NFR-1 | 정확성 | 오탐 최소화. 타입 미확정은 ERROR 아닌 WARNING. |
| NFR-2 | 성능 | 증분 컴파일 지원. 모듈당 분석 오버헤드 빌드 시간 +10% 이내. |
| NFR-3 | 호환성 | Java 17+, **Maven 3.9+**, `maven-compiler-plugin` 연동, QueryDSL 5.x, Hibernate 6.x |
| NFR-4 | 확장성 | 함수 카탈로그 외부 주입으로 커스텀 함수 / `FUNCTION()` 매핑 등록 가능 |
| NFR-5 | 격리성 | 폐쇄망 VDI에서 외부 네트워크 의존 없이 동작 (사내 저장소 배포) |
| NFR-6 | 멀티모듈 | Maven 멀티모듈 프로젝트에서 모듈별 독립 실행 + 루트 집계 리포트 |

---

## 4. 타입 호환성 규칙표 (판정 기준)

Hibernate 6 함수 카테고리별 허용 인자 타입. ERROR/OK 판정의 직접 근거.

| 함수 예시 | 카테고리 | 허용 인자 타입(정규화) | 반환 타입 | String 인자 시 |
|-----------|---------|----------------------|----------|---------------|
| `abs`, `sign`, `floor`, `ceiling` | NUMERIC | Number 계열 | 인자 타입 그대로 | **ERROR** |
| `mod` | NUMERIC(2-arity) | (Integer계열, Integer계열) | 정수 | ERROR/개수오류 |
| `sqrt`, `exp`, `ln`, `power` | NUMERIC | Number 계열 | Double | **ERROR** |
| `length` | STRING | String/CharSequence | Integer | OK |
| `lower`, `upper`, `trim`, `substring` | STRING | String | String | OK |
| `locate` | STRING | (String, String[, Integer]) | Integer | 인자별 검사 |
| `concat` | STRING | String(가변) | String | OK |
| `extract` | DATETIME | (필드, temporal) | Integer/Number | **ERROR**(temporal 아님) |
| `coalesce`, `nullif` | GENERAL | 동일 카테고리 다중 | 인자 공통 타입 | 인자 일관성 검사 |
| `cast` | GENERAL | (any, target-type) | target-type | OK(명시 변환) |

> Number 계열 간(Integer↔BigDecimal)은 OK, 정밀도 손실 가능 조합은 WARNING.

---

## 5. 처리 흐름 (Processing Flow)

```
[pom.xml: Maven 플러그인 선언]
        │  ← FR-6.3: 분석 코어를 annotationProcessorPaths에 자동 주입
        ▼
[javac 컴파일 시작]
        │
        ▼
┌─────────────────────────────┐
│ Round 1: QueryDSL APT        │ → QUser.java 등 Q-클래스 생성
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ Round N: 분석 코어 처리기      │
│   - AST 순회 (FR-2)          │
│   - 인자 타입 정규화 (FR-1)    │
│   - 함수 카탈로그 조회 (FR-3)  │
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ Final Round (processingOver)  │ ← FR-6.4: 모든 Q-클래스 확정 후 검사
│   ① ArgumentsValidator        │
│   ② ReturnTypeResolver        │
└──────────────┬──────────────┘
               ▼
        ┌──────┴──────┐
        ▼             ▼
  ① Messager      ② File Report
  (ERROR/WARN)    (target/jpql-check/)
  IDE 라인점프     json/html/sarif
  빌드 게이트       CI 아티팩트
        │             │
        ▼             ▼
[Maven 플러그인: 게이트 판정 + 리포트 집계]  ← FR-6.2
        │
        ▼
  failOnError=true → BUILD FAILURE
```

---

## 6. pom.xml 구성 예시

```xml
<build>
  <plugins>
    <!-- 사용자는 이 플러그인만 선언하면 됨 -->
    <plugin>
      <groupId>com.ourcompany</groupId>
      <artifactId>jpql-function-type-checker-maven-plugin</artifactId>
      <version>1.0.0</version>
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
        <reportDirectory>${project.build.directory}/jpql-check</reportDirectory>
      </configuration>
    </plugin>
  </plugins>
</build>
```

> 플러그인이 내부적으로 분석 코어 처리기를 QueryDSL 처리기 다음 순서로 `annotationProcessorPaths`에 등록한다(FR-6.3.1). 자동 주입 불가 환경에서는 수동 등록 가이드 제공(FR-6.3.2).

---

## 7. 판정 예시 (Acceptance Criteria 샘플)

| # | 코드 패턴 | 기대 판정 | 진단 출력(핵심 필드) |
|---|-----------|-----------|---------------------|
| 1 | `QUser.user.name.length()` (name=String) | OK | — |
| 2 | `numberOperation(ABS, qUser.name)` (name=String) | **ERROR** | `User.name(String)`, idx 0, 기대 Number |
| 3 | `mod(qOrder.amount)` (인자 1개) | **ERROR** | 개수 불일치, 2 필요 |
| 4 | `abs(qOrder.amount)` (amount=BigDecimal) | OK | — |
| 5 | `abs(expr)` (expr=Expression<?>) | **WARNING** | UNRESOLVED |
| 6 | `extract(YEAR, qUser.name)` (name=String) | **ERROR** | `User.name(String)`, temporal 요구 |
| 7 | `round(qOrder.price, 2)` (JPA3.2) | OK | — |
| 8 | `round(...)` (JPA3.1 환경) | **WARNING** | 상위 버전 전용 |

---

## 8. 범위 외 (Out of Scope)
- 런타임 검증. 동적 `BooleanBuilder` / 문자열 HQL은 정적 추적 불가하므로 미지원.
- DB Dialect별 함수 지원 여부(특정 DB의 `power` 미지원 등)는 검사 안 함 — Hibernate 추상 레벨까지만.
- SQL 인젝션 등 보안 검사.

---

## 9. 후속 검토 사항 (Open Issues)
1. **APT 처리 순서 보장 강건성** — FR-6.4의 `processingOver()` 전략으로 대응하나, 일부 빌드(증분 컴파일/IDE 자체 컴파일러)에서 라운드 동작이 다를 수 있어 IDE별 검증 필요.
2. **함수 카탈로그 Hibernate 버전 동기화** — `CommonFunctionFactory` 등록분 수동 미러링 vs 리플렉션 추출 빌드 스텝. (★ 다음 확정 필요)
3. **멀티모듈 집계 리포트** — NFR-6의 루트 집계를 플러그인 aggregator goal로 둘지, 별도 report goal로 분리할지.
4. **파일 리포트 형식 우선순위** — 거버넌스 연동(SonarQube) 유무에 따라 SARIF 우선순위 결정. (★ 사내 SonarQube 사용 여부 확인 필요)