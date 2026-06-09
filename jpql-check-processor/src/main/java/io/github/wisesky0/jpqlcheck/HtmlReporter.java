package io.github.wisesky0.jpqlcheck;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class HtmlReporter {
    private final Path outputDir;

    public HtmlReporter(Path outputDir) {
        this.outputDir = outputDir;
    }

    public void write(List<Finding> findings) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>JPQL Check Report</title></head><body>");
        sb.append("<h1>JPQL 함수 타입 불일치 리포트</h1>");
        sb.append("<p>총 ").append(findings.size()).append("건</p>");
        sb.append("<table border='1'><tr><th>파일</th><th>위치</th><th>함수</th><th>엔티티.컬럼</th><th>실제타입</th><th>기대타입</th><th>심각도</th><th>근거</th></tr>");
        for (Finding f : findings) {
            sb.append("<tr>");
            sb.append("<td>").append(f.sourceFile()).append("</td>");
            sb.append("<td>[").append(f.line()).append(",").append(f.column()).append("]</td>");
            sb.append("<td>").append(f.functionName()).append("</td>");
            sb.append("<td>").append(f.entity()).append(".").append(f.columnName()).append("</td>");
            sb.append("<td>").append(f.actualType()).append("</td>");
            sb.append("<td>").append(f.expectedType()).append("</td>");
            sb.append("<td style='color:").append("ERROR".equals(f.severity()) ? "red" : "orange").append("'>")
              .append(f.severity()).append("</td>");
            sb.append("<td>").append(f.reason()).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table></body></html>");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("report.html"), sb.toString());
    }
}
