package io.github.wisesky0.jacksoncheck.model;

import java.util.Set;

public record DetectionResult(
    String dtoFqcn,
    RiskLevel riskLevel,
    String constructorSummary,
    Set<DetectionScope> detectedBy,
    String reachPath,
    String inheritanceChain,
    boolean extendsBaseModel,
    boolean abstractFlag,
    String action,
    String sourceFile,
    long line
) {}
