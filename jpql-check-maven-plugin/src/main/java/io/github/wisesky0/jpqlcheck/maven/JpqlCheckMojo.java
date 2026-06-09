package io.github.wisesky0.jpqlcheck.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Mojo(name = "check", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class JpqlCheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "true")
    private boolean failOnError;

    @Parameter(defaultValue = "false")
    private boolean failOnWarning;

    @Parameter(defaultValue = "${project.build.directory}/jpql-check")
    private File reportDirectory;

    @Parameter
    private List<String> reportFormats = List.of("json");

    @Parameter
    private boolean suppressUnresolved = false;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("JPQL 함수 타입 체크 실행 중...");
        getLog().info("리포트 디렉터리: " + reportDirectory);

        if (!reportDirectory.exists()) {
            getLog().info("리포트 디렉터리 없음 - 검출된 타입 불일치 없음");
            return;
        }

        File jsonReport = new File(reportDirectory, "report.json");
        if (!jsonReport.exists()) {
            getLog().info("JSON 리포트 없음 - 검출된 타입 불일치 없음");
            return;
        }

        try {
            String content = Files.readString(jsonReport.toPath());
            int errors = extractCount(content, "\"errors\"");
            int warnings = extractCount(content, "\"warnings\"");

            getLog().info("JPQL 검사 결과: " + errors + " 오류, " + warnings + " 경고");

            if (failOnError && errors > 0) {
                throw new MojoFailureException("JPQL 함수 타입 불일치 오류 " + errors + "건이 검출되었습니다. 리포트: " + jsonReport);
            }
            if (failOnWarning && warnings > 0) {
                throw new MojoFailureException("JPQL 함수 타입 불일치 경고 " + warnings + "건이 검출되었습니다. 리포트: " + jsonReport);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("리포트 읽기 실패: " + e.getMessage(), e);
        }
    }

    private int extractCount(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return 0;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (start == end) return 0;
        return Integer.parseInt(json.substring(start, end));
    }
}
