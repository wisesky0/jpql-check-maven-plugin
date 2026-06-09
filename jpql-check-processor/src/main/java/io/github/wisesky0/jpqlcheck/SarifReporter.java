package io.github.wisesky0.jpqlcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class SarifReporter {
    private final Path outputDir;
    private static final ObjectMapper mapper = new ObjectMapper();

    public SarifReporter(Path outputDir) {
        this.outputDir = outputDir;
    }

    public void write(List<Finding> findings) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", "2.1.0");
        root.put("$schema", "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json");
        ArrayNode runs = root.putArray("runs");
        ObjectNode run = runs.addObject();
        ObjectNode tool = run.putObject("tool");
        ObjectNode driver = tool.putObject("driver");
        driver.put("name", "jpql-check");
        driver.put("version", "1.0.0");
        ArrayNode results = run.putArray("results");
        for (Finding f : findings) {
            ObjectNode result = results.addObject();
            result.put("ruleId", "JPQL_TYPE_MISMATCH");
            result.put("level", "ERROR".equals(f.severity()) ? "error" : "warning");
            ObjectNode message = result.putObject("message");
            message.put("text", f.reason());
            ArrayNode locations = result.putArray("locations");
            ObjectNode loc = locations.addObject();
            ObjectNode physicalLoc = loc.putObject("physicalLocation");
            ObjectNode artifactLoc = physicalLoc.putObject("artifactLocation");
            artifactLoc.put("uri", f.sourceFile());
            ObjectNode region = physicalLoc.putObject("region");
            region.put("startLine", f.line());
            region.put("startColumn", f.column());
        }
        Files.createDirectories(outputDir);
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("results.sarif").toFile(), root);
    }
}
