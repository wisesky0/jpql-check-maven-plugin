# QueryDSL 5.1.0 Expressions.template 계열 메서드 분류 (R-01, R-02)

## 배경

Hibernate 6.6.49.Final의 org.hibernate.query.sqm.internal.TypecheckUtil은 타입이 정적으로 확정된 피연산자에 대해 엄격한 타입 검사를 수행한다. 오류 1은 등록 함수의 인자 위치에 타입 비호환 Path가 바인딩될 때 발생하며, 오류 2는 미등록 네이티브 함수의 반환 타입이 Object로 추론되어 Path 계열과 비교 시 발생한다. 상세 정의는 docs/PRD.md 참조.

## QueryDSL 템플릿 생성 메서드 분류

| 메서드 | 반환 타입 | 오류 1 발생 가능 | 오류 2 발생 가능 | 비고 |
|---|---|---|---|---|
| template(Class<T>, ...) | SimpleTemplate<T> | 가능 | 가능(eq/ne 비교 한정) | 범용 템플릿. SimpleExpression이므로 비교 연산은 eq/ne/in만 제공 |
| simpleTemplate(Class<T>, ...) | SimpleTemplate<T> | 가능 | 가능(eq/ne 비교 한정) | template과 동일 |
| dslTemplate(Class<T>, ...) | DslTemplate<T> | 가능 | 가능(eq/ne 비교 한정) | DSL 표현식 생성용 |
| comparableTemplate(Class<T>, ...) | ComparableTemplate<T> | 가능 | 가능 | goe/gt/loe/lt 등 전체 비교 연산 제공 |
| dateTemplate(Class<T>, ...) | DateTemplate<T> | 가능 | 가능 | 날짜 비교 연산 제공 |
| timeTemplate(Class<T>, ...) | TimeTemplate<T> | 가능 | 가능 | 시간 비교 연산 제공 |
| dateTimeTemplate(Class<T>, ...) | DateTimeTemplate<T> | 가능 | 가능 | 일시 비교 연산 제공 |
| stringTemplate(String, ...) | StringTemplate | 가능 | 가능 | 오류 2의 대표 사례(DATE_FORMAT). 전체 비교 연산 제공 |
| booleanTemplate(String, ...) | BooleanTemplate | 가능 | 가능(낮음) | 보통 술어로 직접 사용되어 비교 결합 빈도 낮음 |
| numberTemplate(Class<T>, ...) | NumberTemplate<T> | 가능 | 가능 | 오류 1의 대표 사례(ABS). 전체 비교 연산 제공 |
| enumTemplate(Class<T>, ...) | EnumTemplate<T> | 가능 | 가능 | enum 비교 |

> **주석**: 각 메서드는 4가지 오버로드(String template + Object... args / String + List<?> / Template + Object... / Template + List<?>)를 가진다.

## 결론

모든 template 계열 메서드는 템플릿 문자열에 등록 함수를 쓸 수 있으므로 오류 1 후보다. 오류 2는 결과 표현식이 비교 연산에 결합될 때만 발생하므로, 비교 연산을 제공하는 모든 반환 타입이 후보다. 따라서 감지 대상은 메서드명이 'Template'로 끝나는 Expressions의 모든 정적 메서드 호출이다.
