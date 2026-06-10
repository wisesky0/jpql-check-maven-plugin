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
        sb.append("<h1>JPQL 타입 불일치 정적 감지 리포트</h1>");
        sb.append("<p>총 ").append(findings.size()).append("건</p>");
        sb.append("<table border='1'><tr>")
          .append("<th>규칙</th><th>심각도</th><th>파일</th><th>위치</th>")
          .append("<th>함수</th><th>템플릿</th><th>대상 표현식</th><th>추론 타입</th>")
          .append("<th>기대 타입</th><th>관련 라인</th><th>근거</th><th>권장 조치</th></tr>");
        for (Finding f : findings) {
            sb.append("<tr>");
            sb.append("<td>").append(esc(f.ruleId())).append("</td>");
            sb.append("<td style='color:").append("ERROR".equals(f.severity()) ? "red" : "orange").append("'>")
              .append(f.severity()).append("</td>");
            sb.append("<td>").append(esc(f.sourceFile())).append("</td>");
            sb.append("<td>[").append(f.line()).append(",").append(f.column()).append("]</td>");
            sb.append("<td>").append(esc(f.functionName())).append("</td>");
            sb.append("<td>").append(esc(f.templateString())).append("</td>");
            sb.append("<td>").append(esc(f.offendingExpression())).append("</td>");
            sb.append("<td>").append(esc(f.inferredType())).append("</td>");
            sb.append("<td>").append(esc(f.expectedType())).append("</td>");
            sb.append("<td>").append(f.relatedLine() >= 0 ? String.valueOf(f.relatedLine()) : "-").append("</td>");
            sb.append("<td>").append(esc(f.reason())).append("</td>");
            sb.append("<td>").append(esc(f.recommendation())).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table></body></html>");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("report.html"), sb.toString());
    }

    private static String esc(String s) {
        if (s == null) return "-";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
