# JPQL 함수 인자 타입 불일치 정적 탐지 도구 테스트 설계서

> **대상 요구사항**: 요구사항 명세서 v2.0
> **테스트 전략**: 컴파일 타임 처리기 검증 + Maven 플러그인 통합 검증 (2계층 구조 대응)

## 1. 테스트 전략 개요

### 1.1 계층별 테스트 분할
도구가 2계층(분석 코어 + Maven 플러그인)이므로, 테스트도 계층별로 분리한다.

| 계층 | 테스트 종류 | 핵심 도구 | 검증 대상 |
|------|-----------|----------|----------|
| 분석 코어 (Annotation Processor) | 단위/컴파일 테스트 | `google-compile-testing` | 판정 로직(FR-1~5), 진단 출력 |
| Maven 플러그인 (Mojo) | 통합 테스트 | `maven-plugin-testing-harness` / `maven-invoker-plugin` | 주입·게이트·리포트(FR-6) |
| 전체 결합 | E2E 테스트 | 실제 샘플 Maven 프로젝트 빌드 | 라운드 순서, 폐쇄망 동작 |

### 1.2 테스트 판정 기준 (Oracle)
불일치 판정의 정답은 **Hibernate 6 실제 동작**이다. 따라서 회색지대 케이스는 실제 Hibernate 6에서 동일 HQL을 실행해 `SemanticException` 발생 여부를 대조하여 기대값을 확정한다(FR-4 기준 정합성 보장).

### 1.3 테스트 데이터 구성
공통 픽스처로 다음 엔티티/Q-클래스를 사용한다.

| 엔티티 | 컬럼(타입) | 용도 |
|--------|-----------|------|
| `User` | `name(String)`, `age(Integer)`, `score(BigDecimal)`, `createdAt(Instant)` | 카테고리별 인자 |
| `Order` | `amount(BigDecimal)`, `qty(Integer)`, `memo(String)` | 숫자/문자 혼합 |
| `Event` | `eventDate(LocalDate)` | temporal 함수용 |

---

## 2. 단위 테스트 — 분석 코어 (FR-1 ~ FR-5)

### 2.1 FR-1: Q-Entity 컬럼 경로 인식 / 타입 정규화

| TC ID | 시나리오 | 입력 | 기대 결과 |
|-------|---------|------|----------|
| UT-1-01 | StringPath 정규화 | `qUser.name` (`StringPath`) | 도메인 타입 `String` 추출 |
| UT-1-02 | NumberPath 제네릭 정규화 | `qUser.score` (`NumberPath<BigDecimal>`) | `BigDecimal` 추출 |
| UT-1-03 | DateTimePath 정규화 | `qUser.createdAt` (`DateTimePath<Instant>`) | `Instant` 추출 |
| UT-1-04 | 와일드카드 미해결 | `Expression<?>` 인자 | UNRESOLVED 표시 (ERROR 아님) |
| UT-1-05 | 제네릭 소거 미해결 | raw `Path` | UNRESOLVED + WARNING |

### 2.2 FR-2: 함수 호출 지점 탐지

| TC ID | 시나리오 | 입력 | 기대 결과 |
|-------|---------|------|----------|
| UT-2-01 | 빌트인 메서드 탐지 | `qUser.name.length()` | 함수 `length`, 인자 1 식별 |
| UT-2-02 | Expressions 직접 호출 | `Expressions.numberOperation(..., ABS, qUser.name)` | 함수 `abs`, 인자 식별 |
| UT-2-03 | 커스텀 template 호출 | `Expressions.template(..., "abs({0})", arg)` | 함수 식별 + 인자 추출 |
| UT-2-04 | 중첩 함수 호출 | `abs(length(qUser.name))` | 내·외부 호출 각각 탐지 |
| UT-2-05 | 함수 외 메서드 무시 | `qUser.name.eq("x")` | 탐지 대상 아님(오탐 없음) |

### 2.3 FR-3 / FR-4: 카탈로그 조회 및 불일치 판정 (핵심)

요구사항 §7 인수 기준을 그대로 테스트화한다.

| TC ID | 코드 패턴 | 기대 판정 | 검증 근거(규칙) |
|-------|----------|----------|----------------|
| UT-4-01 | `qUser.name.length()` (String) | **OK** | STRING 카테고리 일치 |
| UT-4-02 | `abs(qUser.name)` (String) | **ERROR** | NUMERIC ↔ String 비호환 (§4) |
| UT-4-03 | `mod(qOrder.amount)` (1-arity) | **ERROR** | 인자 개수 불일치 (FR-4.2) |
| UT-4-04 | `abs(qOrder.amount)` (BigDecimal) | **OK** | NUMERIC ↔ BigDecimal 호환 |
| UT-4-05 | `abs(Expression<?>)` | **WARNING** | UNRESOLVED (FR-1.3) |
| UT-4-06 | `extract(YEAR, qUser.name)` (String) | **ERROR** | DATETIME temporal 요구 (§4) |
| UT-4-07 | `extract(YEAR, qEvent.eventDate)` (LocalDate) | **OK** | temporal 호환 |
| UT-4-08 | `round(qOrder.amount, 2)` (JPA3.2) | **OK** | 버전 충족 |
| UT-4-09 | `round(...)` (JPA3.1 환경) | **WARNING** | 상위 버전 전용 (FR-4.3) |
| UT-4-10 | `coalesce(qUser.name, qUser.age)` | **WARNING/ERROR** | 카테고리 불일치 인자 혼합 |
| UT-4-11 | `sqrt(qUser.age)` (Integer) | **OK** | Number 계열 호환, 반환 Double |
| UT-4-12 | `mod(qOrder.amount, 2)` (BigDecimal) | **WARNING** | 정밀도 손실 가능(정수 요구) |

### 2.4 FR-3.2: 카탈로그 오버라이드

| TC ID | 시나리오 | 기대 결과 |
|-------|---------|----------|
| UT-3-01 | 외부 `function-catalog.yaml` 주입 | 커스텀 함수 시그니처 반영 |
| UT-3-02 | 카탈로그 미존재 함수 호출 | 검사 스킵 + INFO(미등록 함수 알림) |
| UT-3-03 | 잘못된 YAML 형식 | 명확한 설정 오류 메시지 |

### 2.5 FR-5: 진단 정보 및 출력

#### 진단 정보 정확성 (FR-5.1)
| TC ID | 검증 항목 | 기대 결과 |
|-------|----------|----------|
| UT-5-01 | 소스 파일명 | `UserService.java` 정확 |
| UT-5-02 | 라인·컬럼 위치 | 실제 호출 위치와 일치 |
| UT-5-03 | 엔티티 클래스명 | `User` (Q-클래스 역매핑) |
| UT-5-04 | 컬럼명 | `name` |
| UT-5-05 | 인자 인덱스 | 0-based 정확 |
| UT-5-06 | 기대/실제 타입 | `Number 계열` / `String` |
| UT-5-07 | 판정 근거 문자열 | Validator 규칙 포함 |

#### FR-5.4 엔티티/컬럼 역매핑
| TC ID | 시나리오 | 기대 결과 |
|-------|---------|----------|
| UT-5-08 | 기본 prefix `Q` | `QUser` → `User` (방법 A) |
| UT-5-09 | 커스텀 prefix | PathMetadata 폴백(방법 B)로 추출 |
| UT-5-10 | 역매핑 완전 실패 | 식별자 원문 표기 + WARNING |

#### FR-5.3 출력 채널
| TC ID | 시나리오 | 기대 결과 |
|-------|---------|----------|
| UT-5-11 | Messager ERROR 출력 | `Diagnostic.Kind.ERROR`로 위치 첨부 |
| UT-5-12 | JSON 리포트 생성 | `target/jpql-check/report.json` 스키마 유효 |
| UT-5-13 | HTML 리포트 생성 | 유효 HTML, 검출 항목 렌더 |
| UT-5-14 | SARIF 리포트 생성 | SARIF 2.1.0 스키마 유효 |
| UT-5-15 | 다중 형식 동시 출력 | json+html 동시 생성 |
| UT-5-16 | 검출 0건 | 빈 리포트 정상 생성(예외 없음) |

---

## 3. 통합 테스트 — Maven 플러그인 (FR-6)

| TC ID | 시나리오 | 기대 결과 |
|-------|---------|----------|
| IT-6-01 | `check` goal 실행 | 컴파일과 함께 검사 수행 |
| IT-6-02 | 분석 코어 자동 주입 (FR-6.3.1) | `annotationProcessorPaths`에 등록됨 |
| IT-6-03 | QueryDSL 다음 순서 보장 (FR-6.4) | Q-클래스 생성 후 검사 |
| IT-6-04 | `failOnError=true` + ERROR | BUILD FAILURE |
| IT-6-05 | `failOnError=false` + ERROR | BUILD SUCCESS + 리포트만 |
| IT-6-06 | `failOnWarning=true` + WARNING | BUILD FAILURE |
| IT-6-07 | `reportFormats` 다중 지정 | 지정 형식 모두 생성 |
| IT-6-08 | `reportDirectory` 커스텀 | 지정 경로에 생성 |
| IT-6-09 | `functionCatalog` 외부 경로 | 카탈로그 반영 |
| IT-6-10 | `suppressUnresolved=true` | UNRESOLVED WARNING 억제 |
| IT-6-11 | 수동 등록 모드 (FR-6.3.2) | 가이드대로 등록 시 정상 동작 |
| IT-6-12 | `mvn clean` 후 리포트 정리 | `target/jpql-check/` 삭제 확인 |

---

## 4. E2E 테스트 — 전체 결합

| TC ID | 시나리오 | 기대 결과 |
|-------|---------|----------|
| E2E-01 | 정상 코드만 있는 프로젝트 빌드 | SUCCESS, 검출 0건 |
| E2E-02 | 불일치 포함 프로젝트 빌드 | FAILURE, 정확한 위치·엔티티·컬럼 출력 |
| E2E-03 | 멀티모듈 프로젝트 (NFR-6) | 모듈별 검사 + 루트 집계 리포트 |
| E2E-04 | IDE 내장 컴파일러(IntelliJ) 빌드 | 라운드 순서 정상, IDE Problems 표시 |
| E2E-05 | 증분 컴파일 (NFR-2) | 변경 모듈만 재검사 |
| E2E-06 | 폐쇄망 환경 (NFR-5) | 외부 네트워크 차단 상태에서 정상 동작 |

---

## 5. 비기능 테스트 (NFR)

| TC ID | NFR | 시나리오 | 합격 기준 |
|-------|-----|---------|----------|
| NF-01 | NFR-1 정확성 | 오탐 측정용 정상 코드 100건 | False Positive 0건 |
| NF-02 | NFR-1 정확성 | 미탐 측정용 불일치 코드 50건 | False Negative 0건 |
| NF-03 | NFR-2 성능 | 처리기 ON/OFF 빌드 시간 비교 | 오버헤드 ≤ +10% |
| NF-04 | NFR-3 호환성 | Java 17 / 21 매트릭스 | 전 버전 정상 |
| NF-05 | NFR-3 호환성 | Maven 3.9.x | 정상 동작 |
| NF-06 | NFR-6 멀티모듈 | 10+ 모듈 프로젝트 | 집계 리포트 정확 |

---

## 6. 경계·예외 테스트 (Edge Cases)

| TC ID | 시나리오 | 기대 결과 |
|-------|---------|----------|
| EC-01 | Q-클래스 미생성 상태(QueryDSL 처리기 누락) | 명확한 안내 메시지, 무한루프 없음 |
| EC-02 | 동일 라인 다중 함수 호출 | 각각 독립 진단 |
| EC-03 | 깊은 중첩(5단계 이상) 함수 | 스택 오버플로 없이 처리 |
| EC-04 | 대규모 파일(수천 호출 지점) | 메모리 누수 없음 |
| EC-05 | 동적 인자(메서드 반환값) | UNRESOLVED 안전 처리 |
| EC-06 | `@SuppressWarnings` 부착 지점 | 해당 WARNING 억제 |

---

## 7. 요구사항 추적 매트릭스 (Traceability)

| 요구사항 | 검증 테스트 |
|---------|-----------|
| FR-1 (컬럼 인식/정규화) | UT-1-01 ~ UT-1-05 |
| FR-2 (호출 탐지) | UT-2-01 ~ UT-2-05 |
| FR-3 (카탈로그) | UT-3-01 ~ UT-3-03 |
| FR-4 (불일치 판정) | UT-4-01 ~ UT-4-12 |
| FR-5.1/5.2 (진단 정보) | UT-5-01 ~ UT-5-07 |
| FR-5.3 (출력 채널) | UT-5-11 ~ UT-5-16 |
| FR-5.4 (역매핑) | UT-5-08 ~ UT-5-10 |
| FR-6 (Maven 플러그인) | IT-6-01 ~ IT-6-12 |
| NFR-1 (정확성) | NF-01, NF-02 |
| NFR-2 (성능) | NF-03, EC-04 |
| NFR-3 (호환성) | NF-04, NF-05 |
| NFR-5 (격리성) | E2E-06 |
| NFR-6 (멀티모듈) | E2E-03, NF-06 |

> 미커버 요구사항이 없는지 매트릭스로 확인 — 모든 FR/NFR이 1개 이상 테스트에 매핑됨.

---

## 8. 합격 판정 기준 (Exit Criteria)
- 모든 ERROR/OK 판정 테스트(UT-4-*) 100% 통과
- False Positive(NF-01) 0건, False Negative(NF-02) 0건
- 요구사항 추적 매트릭스 전 항목 커버
- 성능 오버헤드 ≤ +10% (NF-03)
- 폐쇄망 E2E(E2E-06) 통과

---

## 9. 미결 사항 (테스트 설계 의존성)
1. **판정 기준 정합 검증 자동화** — §1.2의 "실제 Hibernate 6 대조"를 수동으로 할지, 테스트에 임베디드 Hibernate를 띄워 자동 대조할지. 후자가 정확하나 폐쇄망 의존성 관리 필요.
2. **카탈로그 동기화 테스트** — 요구사항 §9-2(수동 미러링 vs 리플렉션 추출) 결정에 따라 UT-3 계열 테스트 구성이 달라짐.
3. **리포트 형식 우선순위** — SonarQube 연동 여부 확정 시 UT-5-14(SARIF) 우선순위 조정.