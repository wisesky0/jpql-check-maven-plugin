# 작업 진행 상황 (PRD v3.2 기반)

> 최종 갱신: 2026-06-10
> 브랜치: `claude/tender-dirac-tnxc7q`

## 작업 계획

PRD v3.2(docs/PRD.md)를 기준으로 기존 코드(구 PRD 기반)를 재작성한다.

| # | 작업 | PRD 근거 | 담당 | 상태 |
|---|---|---|---|---|
| 1 | 작업 계획 수립 및 progress.md 생성 | - | claude | ✅ 완료 |
| 2 | 분석 문서: QueryDSL template 계열 분류 | R-01, R-02 | simple-worker | ✅ 완료 |
| 3 | 분석 문서: Hibernate 함수 카탈로그 | R-03, R-04 | simple-worker | ✅ 완료 |
| 4 | 함수 카탈로그 리소스 분리 (jpql-functions.json) | I-02 | claude | ✅ 완료 |
| 5 | Finding 모델 재설계 (ruleId, 템플릿 원문, 관련 라인 등) | I-05, I-06 | claude | ✅ 완료 |
| 6 | 템플릿 문자열 파서 (함수 추출, cast/function() 인식) | E-01, E-02 | claude | ✅ 완료 |
| 7 | 감지 엔진 재작성: Pass 0/1/2 3단계 판정 | D-01~D-08, 5장 | claude | ✅ 완료 |
| 8 | 리포터(JSON/HTML/SARIF) 신규 Finding 반영 | I-05, I-06 | claude | ✅ 완료 |
| 9 | 수용 기준 테스트 TC-01~TC-18 + I-06 검증 작성 | 7장 | claude | ✅ 완료 |
| 10 | 기존 테스트 정리(신규 모델 반영) | - | claude | ✅ 완료 |
| 11 | 전체 빌드/테스트 검증 (`mvn verify`) | - | claude | ✅ 완료 (63/63 통과) |
| 12 | 커밋/푸시 및 progress.md 최종 갱신 | - | claude | ✅ 완료 |

## 최종 결과

| 모듈 | 결과 |
|---|---|
| `jpql-check-processor` | 63개 테스트 전 통과 |
| `jpql-check-maven-plugin` | 빌드 성공 (install) |

## 주요 구현 내용

### 새로 추가된 파일
| 파일 | 내용 |
|---|---|
| `src/main/resources/jpql-functions.json` | Hibernate 6.6.49.Final 등록 함수 카탈로그 (I-02, R-03) |
| `src/main/java/.../TemplateParser.java` | 템플릿 문자열 파서 — 함수 추출, cast/function() 판별 |
| `src/test/java/.../AcceptanceCriteriaTest.java` | TC-01~TC-18 수용 기준 테스트 (PRD 7장) |
| `docs/analysis/querydsl-template-methods.md` | R-01, R-02 분석 문서 |
| `docs/analysis/hibernate-function-catalog.md` | R-03, R-04 분석 문서 |

### 재작성된 파일
| 파일 | 변경 내용 |
|---|---|
| `JpqlFunctionTypeProcessor.java` | Pass 0(위험 변수 수집) / Pass 1(템플릿 직접 검사) / Pass 2(변수 경유 비교 검사) 3단계 판정. TaskListener 기반 ANALYZE 완료 후 스캔으로 TypeMirror 안정성 확보 |
| `FunctionCatalog.java` | jpql-functions.json 에서 로드, 폴백 내장값 유지 |
| `Finding.java` | ruleId, templateString, offendingExpression, inferredType, relatedLine, recommendation 필드 추가 (I-05, I-06) |
| `JsonReporter.java`, `HtmlReporter.java`, `SarifReporter.java` | 신규 Finding 필드 반영. SARIF에 D-08 relatedLocations 포함 |
| `TypeNormalizer.java` | Comparable/Enum/Simple Path 패턴 추가 |

## 설계 결정 사항

- **TaskListener 기반 스캔**: javac 라운드 기반 처리는 타입 속성(attribution) 이전에 실행되어 `typeOf(expr)`이 null을 반환. `TaskEvent.Kind.ANALYZE` 완료 후 스캔하도록 변경하여 비교 대상 인자의 TypeMirror 안정성 확보. 환경에 따라 TaskListener 등록 실패 시 기존 라운드 기반으로 폴백.
- **Path/Constant 분류**: `Types.isSubtype`으로 `querydsl.core.types.Path` / `Constant` 계층 판정, 폴백 휴리스틱으로 타입명 기반 분류 병행.
- **감지 범위**: `Expressions.*Template(..)` 직접 호출과 동일 메서드 내 로컬 변수 대입 경유만 감지. inter-method 추적은 범위 외(PRD 9장).
- **바인딩 보수성**: 템플릿 인자 위치가 단일 플레이스홀더(`{n}`)가 아닌 복합식이면 오탐 방지를 위해 판정 건너뜀.

## 이슈 / 메모

- (없음)
