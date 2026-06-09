package io.github.wisesky0.jpqlcheck;

public record Finding(
    String sourceFile,
    long line,
    long column,
    String functionName,
    String entity,
    String columnName,
    String actualType,
    String expectedType,
    String severity,  // ERROR, WARNING, INFO
    String reason
) {}
