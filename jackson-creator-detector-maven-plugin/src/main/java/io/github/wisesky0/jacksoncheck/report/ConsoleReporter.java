package io.github.wisesky0.jacksoncheck.report;

import io.github.wisesky0.jacksoncheck.model.DetectionResult;
import io.github.wisesky0.jacksoncheck.model.RiskLevel;
import org.apache.maven.plugin.logging.Log;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ConsoleReporter {
    private final Log log;

    public ConsoleReporter(Log log) { this.log = log; }

    public void report(List<DetectionResult> results) {
        long risky = results.stream().filter(r -> r.riskLevel().isRisky()).count();
        long safe   = results.stream().filter(r -> r.riskLevel() == RiskLevel.SAFE).count();
        long excl   = results.stream().filter(r -> r.riskLevel() == RiskLevel.EXCLUDED).count();

        log.info("═══ Jackson Creator Detector 결과 ═══");
        log.info(String.format("  총 분석 타입 : %d  (위험 %d / 안전 %d / 제외 %d)",
            results.size(), risky, safe, excl));

        Map<RiskLevel, Long> byLevel = results.stream()
            .filter(r -> r.riskLevel().isRisky())
            .collect(Collectors.groupingBy(DetectionResult::riskLevel, Collectors.counting()));
        for (RiskLevel r : new RiskLevel[]{RiskLevel.R1, RiskLevel.R2, RiskLevel.R3, RiskLevel.R4}) {
            long cnt = byLevel.getOrDefault(r, 0L);
            if (cnt > 0) log.info(String.format("  %s: %d건", r.name(), cnt));
        }

        for (DetectionResult r : results) {
            if (!r.riskLevel().isRisky()) continue;
            log.warn(String.format("[%s] %s  (%s:%d)",
                r.riskLevel().name(), r.dtoFqcn(), r.sourceFile(), r.line()));
            log.warn("       " + r.riskLevel().getDescription());
            log.warn("       생성자: " + r.constructorSummary());
            log.warn("       감지범위: " + r.detectedBy());
        }
        log.info("═══════════════════════════════════════");
    }
}
