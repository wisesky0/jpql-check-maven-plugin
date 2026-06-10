package io.github.wisesky0.jpqlcheck;

import io.github.wisesky0.jpqlcheck.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for report output (JSON, SARIF, HTML reporters).
 */
class ReportOutputTest {

    @TempDir
    Path tempDir;

    @Test
    void jsonReporter_createsReportFile() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new JsonReporter(tempDir).write(findings);

        Path reportFile = tempDir.resolve("report.json");
        assertTrue(Files.exists(reportFile), "report.json should be created");
    }

    @Test
    void jsonReporter_containsFindings() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new JsonReporter(tempDir).write(findings);

        String content = Files.readString(tempDir.resolve("report.json"));
        assertTrue(content.contains("findings"), "JSON should contain 'findings' key");
        assertTrue(content.contains("totalFindings"), "JSON should contain 'totalFindings'");
    }

    @Test
    void jsonReporter_totalFindingsIsCorrect() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new JsonReporter(tempDir).write(findings);

        String content = Files.readString(tempDir.resolve("report.json"));
        assertTrue(content.contains("\"totalFindings\" : 3"),
            "totalFindings should be 3, content: " + content);
    }

    @Test
    void jsonReporter_errorsAndWarningsCount() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new JsonReporter(tempDir).write(findings);

        String content = Files.readString(tempDir.resolve("report.json"));
        assertTrue(content.contains("\"errors\" : 2"), "errors should be 2, content: " + content);
        assertTrue(content.contains("\"warnings\" : 1"), "warnings should be 1, content: " + content);
    }

    @Test
    void jsonReporter_emptyFindings() throws IOException {
        new JsonReporter(tempDir).write(List.of());

        String content = Files.readString(tempDir.resolve("report.json"));
        assertTrue(content.contains("\"totalFindings\" : 0"));
    }

    @Test
    void sarifReporter_createsResultsFile() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new SarifReporter(tempDir).write(findings);

        Path sarifFile = tempDir.resolve("results.sarif");
        assertTrue(Files.exists(sarifFile), "results.sarif should be created");
    }

    @Test
    void sarifReporter_containsVersion() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new SarifReporter(tempDir).write(findings);

        String content = Files.readString(tempDir.resolve("results.sarif"));
        assertTrue(content.contains("\"version\" : \"2.1.0\""), "SARIF should have version 2.1.0");
    }

    @Test
    void sarifReporter_containsRuleId() throws IOException {
        List<Finding> findings = List.of(Fixtures.error1Finding("abs", "x.y", "java.lang.String", "NUMERIC"));
        new SarifReporter(tempDir).write(findings);

        String content = Files.readString(tempDir.resolve("results.sarif"));
        assertTrue(content.contains("\"ruleId\" : \"D-01\""), "SARIF should contain PRD rule id D-01");
    }

    @Test
    void sarifReporter_d08FindingHasRelatedLocation() throws IOException {
        List<Finding> findings = List.of(Fixtures.error2Finding("date_format", "order.stringDttm", 15L));
        new SarifReporter(tempDir).write(findings);

        String content = Files.readString(tempDir.resolve("results.sarif"));
        assertTrue(content.contains("relatedLocations"), "I-06: D-08 finding should include related location");
        assertTrue(content.contains("\"startLine\" : 15"));
    }

    @Test
    void htmlReporter_createsHtmlFile() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new HtmlReporter(tempDir).write(findings);

        Path htmlFile = tempDir.resolve("report.html");
        assertTrue(Files.exists(htmlFile), "report.html should be created");
    }

    @Test
    void htmlReporter_containsTableRows() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new HtmlReporter(tempDir).write(findings);

        String content = Files.readString(tempDir.resolve("report.html"));
        assertTrue(content.contains("<table"), "HTML should contain a table");
        assertTrue(content.contains("<tr>"), "HTML should have table rows");
    }

    @Test
    void htmlReporter_showsSeverityColors() throws IOException {
        List<Finding> findings = Fixtures.sampleFindings();
        new HtmlReporter(tempDir).write(findings);

        String content = Files.readString(tempDir.resolve("report.html"));
        assertTrue(content.contains("color:red") || content.contains("color: red"),
            "HTML should show errors in red");
    }
}
