package io.github.wisesky0.jacksoncheck.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.github.wisesky0.jacksoncheck.model.DetectionResult;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public class JsonDetectionReporter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path outputDir;

    public JsonDetectionReporter(Path outputDir) { this.outputDir = outputDir; }

    public void report(List<DetectionResult> results) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("totalAnalyzed", results.size());
        root.put("riskyCount", results.stream().filter(r -> r.riskLevel().isRisky()).count());

        ArrayNode arr = root.putArray("findings");
        for (DetectionResult r : results) {
            if (!r.riskLevel().isRisky()) continue;
            ObjectNode node = arr.addObject();
            node.put("dtoFqcn", r.dtoFqcn());
            node.put("riskLevel", r.riskLevel().name());
            node.put("description", r.riskLevel().getDescription());
            node.put("constructorSummary", r.constructorSummary());
            node.put("action", r.action());
            node.put("abstractFlag", r.abstractFlag());
            node.put("extendsBaseModel", r.extendsBaseModel());
            node.put("sourceFile", r.sourceFile());
            node.put("line", r.line());
            ArrayNode scopes = node.putArray("detectedBy");
            r.detectedBy().forEach(s -> scopes.add(s.name()));
            if (r.reachPath() != null) node.put("reachPath", r.reachPath());
            if (r.inheritanceChain() != null) node.put("inheritanceChain", r.inheritanceChain());
        }

        Files.createDirectories(outputDir);
        MAPPER.writerWithDefaultPrettyPrinter()
            .writeValue(outputDir.resolve("jackson-creator-report.json").toFile(), root);
    }
}
