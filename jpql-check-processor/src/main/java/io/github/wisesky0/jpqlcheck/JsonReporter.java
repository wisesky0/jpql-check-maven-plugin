package io.github.wisesky0.jpqlcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class JsonReporter {
    private final Path outputDir;
    private static final ObjectMapper mapper = new ObjectMapper();

    public JsonReporter(Path outputDir) {
        this.outputDir = outputDir;
    }

    public void write(List<Finding> findings) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode arr = root.putArray("findings");
        for (Finding f : findings) {
            ObjectNode node = arr.addObject();
            node.put("sourceFile", f.sourceFile());
            node.put("line", f.line());
            node.put("column", f.columnName());
            node.put("functionName", f.functionName());
            node.put("entity", f.entity());
            node.put("actualType", f.actualType());
            node.put("expectedType", f.expectedType());
            node.put("severity", f.severity());
            node.put("reason", f.reason());
        }
        root.put("totalFindings", findings.size());
        root.put("errors", (int) findings.stream().filter(f -> "ERROR".equals(f.severity())).count());
        root.put("warnings", (int) findings.stream().filter(f -> "WARNING".equals(f.severity())).count());
        Files.createDirectories(outputDir);
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("report.json").toFile(), root);
    }
}
