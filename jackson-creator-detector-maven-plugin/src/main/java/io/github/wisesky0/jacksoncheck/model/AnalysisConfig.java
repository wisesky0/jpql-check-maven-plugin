package io.github.wisesky0.jacksoncheck.model;

import java.util.List;
import java.util.Set;

public record AnalysisConfig(
    Set<DetectionScope> scopes,
    String baseModelFqcn,
    List<String> basePackages,
    List<String> excludePackages
) {}
