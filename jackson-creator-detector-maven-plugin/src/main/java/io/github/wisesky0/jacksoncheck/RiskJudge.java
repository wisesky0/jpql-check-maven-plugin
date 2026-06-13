package io.github.wisesky0.jacksoncheck;

import io.github.wisesky0.jacksoncheck.model.ClassInfo;
import io.github.wisesky0.jacksoncheck.model.ConstructorShape;
import io.github.wisesky0.jacksoncheck.model.RiskLevel;

public final class RiskJudge {

    private RiskJudge() {}

    public static RiskLevel judge(ClassInfo info) {
        // S4: enum/interface → EXCLUDED
        if (info.isEnum || info.isInterface) return RiskLevel.EXCLUDED;

        // S3: record → SAFE (count only)
        if (info.isRecord) return RiskLevel.SAFE;

        ConstructorShape cs = info.constructors;

        // S2: @JsonCreator or @Jacksonized → SAFE
        if (cs.hasJsonCreatorOnCtor || cs.hasJacksonized()) return RiskLevel.SAFE;

        // S1: has no-arg constructor → SAFE
        if (cs.hasNoArgConstructor()) return RiskLevel.SAFE;

        // S5: abstract → SAFE for judgment (report_only), callers handle separately
        // (abstract classes are reported but not flagged as risky per spec)

        boolean hasSingle = cs.hasSingleArgConstructor();
        boolean hasMulti = cs.hasMultiArgConstructor();
        boolean hasAllArgsLombok = cs.hasAllArgsConstructor();

        // R1: single-arg + all-args constructor present
        if (hasSingle && hasAllArgsLombok) return RiskLevel.R1;
        if (hasSingle && hasMulti) return RiskLevel.R1; // multi includes all-args case

        // R2: single-arg only
        if (hasSingle && !hasMulti && !hasAllArgsLombok) return RiskLevel.R2;

        // R3: 2+ arg constructors (no single, no no-arg)
        if (hasMulti && !hasSingle) return RiskLevel.R3;

        // R4: all-args lombok only (no explicit constructors, no no-arg)
        if (!hasSingle && !hasMulti && hasAllArgsLombok) return RiskLevel.R4;

        // No explicit constructors + no lombok → implicit no-arg → SAFE
        if (cs.explicitParamCounts.isEmpty() && cs.lombokAnnotations.isEmpty()) return RiskLevel.SAFE;

        return RiskLevel.SAFE;
    }
}
