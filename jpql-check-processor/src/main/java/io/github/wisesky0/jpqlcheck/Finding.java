package io.github.wisesky0.jpqlcheck;

/**
 * 감지 결과 1건 (PRD I-05, I-06).
 *
 * @param ruleId              감지 규칙 ID (D-01, D-03, D-08, I-03)
 * @param severity            ERROR | WARNING
 * @param sourceFile          소스 파일명
 * @param line                감지 지점 라인
 * @param column              감지 지점 컬럼
 * @param templateString      템플릿 문자열 원문 (미해석 시 null)
 * @param functionName        문제 함수명
 * @param offendingExpression 문제 인자 또는 비교 대상 표현식의 소스 텍스트
 * @param inferredType        문제 표현식의 추론 Java 타입
 * @param expectedType        기대 타입/카테고리 (오류 2의 경우 비교 대상 타입)
 * @param relatedLine         변수 경유 패턴(D-08)에서 템플릿이 대입된 라인. 해당 없으면 -1 (PRD I-06)
 * @param reason              판정 근거
 * @param recommendation      권장 우회 방법 (PRD E-01/E-02 형식)
 */
public record Finding(
    String ruleId,
    String severity,
    String sourceFile,
    long line,
    long column,
    String templateString,
    String functionName,
    String offendingExpression,
    String inferredType,
    String expectedType,
    long relatedLine,
    String reason,
    String recommendation
) {}
