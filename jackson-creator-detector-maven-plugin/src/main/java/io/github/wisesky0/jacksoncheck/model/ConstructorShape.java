package io.github.wisesky0.jacksoncheck.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ConstructorShape {
    public final List<Integer> explicitParamCounts = new ArrayList<>();
    public boolean hasJsonCreatorOnCtor;
    public final Set<String> lombokAnnotations = new HashSet<>(); // simple names

    // Derived helpers
    public boolean hasNoArgLombok() {
        return lombokAnnotations.contains("NoArgsConstructor");
    }
    public boolean hasAllArgsLombok() {
        return lombokAnnotations.contains("AllArgsConstructor")
            || lombokAnnotations.contains("Value");
    }
    public boolean hasRequiredArgsLombok() {
        return lombokAnnotations.contains("RequiredArgsConstructor")
            || lombokAnnotations.contains("Data");
    }
    public boolean hasBuilderLombok() {
        return lombokAnnotations.contains("Builder");
    }
    public boolean hasJacksonized() {
        return lombokAnnotations.contains("Jacksonized");
    }

    /** Derived: does any path produce a no-arg constructor? */
    public boolean hasNoArgConstructor() {
        if (explicitParamCounts.contains(0)) return true;
        if (hasNoArgLombok()) return true;
        return false;
    }

    /** Derived: does any path produce a single-arg constructor? */
    public boolean hasSingleArgConstructor() {
        if (explicitParamCounts.contains(1)) return true;
        if (hasRequiredArgsLombok() && !hasNoArgConstructor()) {
            // @RequiredArgsConstructor/@Data with exactly 1 @NonNull/final field — heuristic: may be single-arg
            // Conservative: only explicit detection is reliable here
        }
        return false;
    }

    /** Derived: does any path produce a multi-arg (2+) constructor? */
    public boolean hasMultiArgConstructor() {
        return explicitParamCounts.stream().anyMatch(c -> c >= 2);
    }

    /** Derived: is there an all-args Lombok constructor? */
    public boolean hasAllArgsConstructor() {
        return hasAllArgsLombok() || hasBuilderLombok();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("explicit=").append(explicitParamCounts);
        sb.append(" lombok=[").append(String.join(",", lombokAnnotations)).append("]");
        if (hasJsonCreatorOnCtor) sb.append(" @JsonCreator");
        return sb.toString();
    }
}
