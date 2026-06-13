package io.github.wisesky0.jacksoncheck;

import io.github.wisesky0.jacksoncheck.model.*;
import io.github.wisesky0.jacksoncheck.report.*;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import javax.tools.*;
import java.io.*;
import java.util.*;

@Mojo(name = "check",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.COMPILE)
public class JacksonCreatorDetectorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "false")
    private boolean failOnFound;

    @Parameter(defaultValue = "R2")
    private String failSeverityThreshold;

    @Parameter
    private List<String> reportFormats = List.of("CONSOLE", "JSON");

    @Parameter(defaultValue = "${project.build.directory}/jackson-creator-report")
    private File reportOutputDirectory;

    @Parameter
    private List<String> detectionScopes = List.of("CACHE_REACHABLE", "BASE_MODEL_HIERARCHY");

    @Parameter(defaultValue = "com.lguplus.wafful.framework.model.BaseModel")
    private String baseModelFqcn;

    @Parameter
    private List<String> basePackages = List.of();

    @Parameter
    private List<String> excludePackages = List.of();

    @Parameter(defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Jackson Creator Detector skipped.");
            return;
        }

        // 4.1: JRE fail-fast (JDK 필수)
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new MojoExecutionException(
                "JDK가 필요합니다(JRE 감지). javac Compiler API를 사용할 수 없습니다. " +
                "Maven을 JDK로 실행하십시오.");
        }

        // 소스 파일 수집
        List<File> sourceFiles = collectSourceFiles();
        if (sourceFiles.isEmpty()) {
            getLog().info("분석할 Java 소스 파일이 없습니다.");
            return;
        }
        getLog().info("Jackson Creator Detector: " + sourceFiles.size() + "개 소스 파일 분석 중...");

        // 컴파일 클래스패스 수집
        List<String> classpathElements;
        try {
            classpathElements = project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("컴파일 클래스패스 해석 실패", e);
        }

        // 분석 실행
        Set<DetectionScope> scopes = new LinkedHashSet<>();
        for (String s : detectionScopes) {
            try { scopes.add(DetectionScope.valueOf(s.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException e) { getLog().warn("알 수 없는 감지 범위: " + s); }
        }

        AnalysisConfig config = new AnalysisConfig(scopes, baseModelFqcn, basePackages, excludePackages);
        List<DetectionResult> results;
        try {
            results = new AnalysisEngine(getLog()).analyze(compiler, sourceFiles, classpathElements, config);
        } catch (Exception e) {
            throw new MojoExecutionException("Jackson Creator 분석 실패: " + e.getMessage(), e);
        }

        // 리포트 출력
        for (String format : reportFormats) {
            switch (format.toUpperCase(Locale.ROOT)) {
                case "CONSOLE" -> new ConsoleReporter(getLog()).report(results);
                case "JSON" -> {
                    try {
                        new JsonDetectionReporter(reportOutputDirectory.toPath()).report(results);
                        getLog().info("JSON 리포트: " + reportOutputDirectory + "/jackson-creator-report.json");
                    } catch (IOException e) {
                        throw new MojoExecutionException("JSON 리포트 생성 실패", e);
                    }
                }
                default -> getLog().warn("알 수 없는 리포트 형식: " + format);
            }
        }

        // failOnFound 게이트 (소스 파일 무변경)
        if (failOnFound) {
            RiskLevel threshold;
            try { threshold = RiskLevel.valueOf(failSeverityThreshold.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { threshold = RiskLevel.R2; }
            final RiskLevel fThreshold = threshold;
            long failCount = results.stream()
                .filter(r -> r.riskLevel().isRisky() && r.riskLevel().atLeastAsRiskyAs(fThreshold))
                .count();
            if (failCount > 0) {
                throw new MojoFailureException(
                    "Jackson creator 위험 패턴 " + failCount + "건 검출됨 (소스 무변경). " +
                    "리포트: " + reportOutputDirectory + "/jackson-creator-report.json");
            }
        }
    }

    private List<File> collectSourceFiles() {
        List<File> files = new ArrayList<>();
        List<String> sourceRoots = new ArrayList<>(project.getCompileSourceRoots());

        // generated-sources 하위 디렉터리도 포함
        File generatedSources = new File(project.getBuild().getDirectory(), "generated-sources");
        if (generatedSources.exists()) {
            File[] subdirs = generatedSources.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File d : subdirs) sourceRoots.add(d.getAbsolutePath());
            }
        }

        for (String root : sourceRoots) {
            File dir = new File(root);
            if (dir.exists()) collectJavaFiles(dir, files);
        }
        // T5: 결정성을 위해 정렬
        files.sort(Comparator.comparing(File::getAbsolutePath));
        return files;
    }

    private void collectJavaFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File f : children) {
            if (f.isDirectory()) collectJavaFiles(f, files);
            else if (f.getName().endsWith(".java")) files.add(f);
        }
    }
}
