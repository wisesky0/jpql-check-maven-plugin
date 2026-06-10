# Hibernate 6.6.49.Final 등록 함수 카탈로그 및 오류 발생 가능성 분류 (R-03, R-04)

## 배경

Hibernate 6.6.49.Final의 org.hibernate.query.sqm.internal.TypecheckUtil은 타입이 정적으로 확정된 피연산자에 대해 엄격한 타입 검사를 수행한다. 오류 1은 등록 함수의 인자 위치에 타입 비호환 Path가 바인딩될 때 발생하며, 오류 2는 미등록 네이티브 함수의 반환 타입이 Object로 추론되어 Path 계열과 비교 시 발생한다. 상세 정의는 docs/PRD.md 참조.

## 등록 함수 카탈로그

| 함수 | 인자 (타입 카테고리) | 반환 | 오류 1 가능성 | 비고 |
|---|---|---|---|---|
| abs | 1: NUMERIC | NUMERIC | 높음 | 문자열 Path 바인딩 시 즉시 발생 (PRD 오류 1 대표 사례) |
| ceiling | 1: NUMERIC | NUMERIC | 높음 | |
| floor | 1: NUMERIC | NUMERIC | 높음 | |
| sign | 1: NUMERIC | NUMERIC | 높음 | |
| sqrt | 1: NUMERIC | NUMERIC | 높음 | |
| exp | 1: NUMERIC | NUMERIC | 높음 | |
| ln | 1: NUMERIC | NUMERIC | 높음 | |
| power | 2: NUMERIC, NUMERIC | NUMERIC | 높음 | |
| mod | 2: NUMERIC, NUMERIC | NUMERIC | 높음 | |
| round | 1~2: NUMERIC, NUMERIC | NUMERIC | 높음 | |
| length | 1: STRING | NUMERIC | 높음 | 숫자 Path 바인딩 시 발생 |
| lower | 1: STRING | STRING | 높음 | |
| upper | 1: STRING | STRING | 높음 | |
| trim | 1: STRING | STRING | 높음 | |
| substring | 2~3: STRING, NUMERIC, NUMERIC | STRING | 높음 | |
| locate | 2~3: STRING, STRING, NUMERIC | NUMERIC | 높음 | |
| concat | 2~N: STRING... | STRING | 중간 | Hibernate 6는 일부 암묵 변환 허용 |
| extract | 2: 필드 식별자, DATETIME | NUMERIC | 높음 | 두 번째 인자가 날짜/시간이어야 함 |
| coalesce | 2~N: 동일 카테고리 | 인자와 동일 | 중간 | 인자 간 카테고리 불일치 시 발생 |
| nullif | 2: 동일 카테고리 | 인자와 동일 | 중간 | |
| cast | 2: 임의, 대상 타입 | 대상 타입 | 없음 | 오류 1의 공식 우회 수단 (PRD E-01) |
| current_date | 0 | DATE | 없음 | |
| current_time | 0 | TIME | 없음 | |
| current_timestamp | 0 | DATETIME | 없음 | |
| size | 1: 컬렉션 경로 | NUMERIC | 낮음 | |
| str | 1: 임의 | STRING | 없음 | |

## 오류 발생 가능성 분석

오류 2 관련: 위 등록 함수들은 Hibernate가 반환 타입을 알고 있으므로 오류 2(Object 추론)를 유발하지 않는다. 오류 2는 이 카탈로그에 존재하지 않는 함수명(예: MySQL DATE_FORMAT, STR_TO_DATE, DATEDIFF, IFNULL 등 DB 네이티브 함수)이 템플릿에 직접 호출 형태로 등장할 때만 발생한다. 따라서 감지 시 '카탈로그에 없는 함수명 = 미등록 함수'로 판정한다(PRD R-04).

이 카탈로그는 jpql-check-processor의 리소스 파일(jpql-functions.json)로 관리되며(PRD I-02), 본 문서와 리소스 파일은 함께 갱신되어야 한다.
