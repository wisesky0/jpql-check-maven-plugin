package io.github.wisesky0.jacksoncheck;

import io.github.wisesky0.jacksoncheck.model.*;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static io.github.wisesky0.jacksoncheck.model.DetectionScope.*;
import static io.github.wisesky0.jacksoncheck.model.RiskLevel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AnalysisEngineIntegrationTest {

    @TempDir
    Path tempDir;

    private static final String BASE_MODEL_FQCN = "com.example.BaseModel";

    // ── helpers ──────────────────────────────────────────────────────────

    private File write(String relPath, String content) throws Exception {
        Path file = tempDir.resolve(relPath.replace('/', File.separatorChar));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file.toFile();
    }

    private List<String> testClasspath() {
        return Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));
    }

    private List<DetectionResult> analyze(List<File> sources, AnalysisConfig config) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "JDK 필요 — JRE 환경은 건너뜁니다");
        return new AnalysisEngine(noopLog())
            .analyze(ToolProvider.getSystemJavaCompiler(), sources, testClasspath(), config);
    }

    private File baseModelStub() throws Exception {
        return write("com/example/BaseModel.java", """
            package com.example;
            public abstract class BaseModel {}
            """);
    }

    private File cacheableStub() throws Exception {
        return write("org/springframework/cache/annotation/Cacheable.java", """
            package org.springframework.cache.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface Cacheable {}
            """);
    }

    // ── BASE_MODEL_HIERARCHY 범위 ─────────────────────────────────────────

    @Test
    void baseModelHierarchy_r1_detected() throws Exception {
        File baseModel = baseModelStub();
        File dto = write("com/example/dto/R1Dto.java", """
            package com.example.dto;
            import com.example.BaseModel;
            public class R1Dto extends BaseModel {
                public R1Dto(String s) {}
                public R1Dto(String s, int n) {}
            }
            """);

        AnalysisConfig config = new AnalysisConfig(
            Set.of(BASE_MODEL_HIERARCHY), BASE_MODEL_FQCN, List.of(), List.of());
        List<DetectionResult> results = analyze(List.of(baseModel, dto), config);

        DetectionResult r = results.stream()
            .filter(x -> "com.example.dto.R1Dto".equals(x.dtoFqcn()))
            .findFirst().orElseThrow(() -> new AssertionError("R1Dto 미검출. 결과: " + results));
        assertEquals(R1, r.riskLevel());
        assertTrue(r.extendsBaseModel());
        assertTrue(r.detectedBy().contains(BASE_MODEL_HIERARCHY));
    }

    @Test
    void baseModelHierarchy_noArgCtor_safe() throws Exception {
        File baseModel = baseModelStub();
        File dto = write("com/example/dto/SafeDto.java", """
            package com.example.dto;
            import com.example.BaseModel;
            public class SafeDto extends BaseModel {
                public SafeDto() {}
                public SafeDto(String s) {}
            }
            """);

        AnalysisConfig config = new AnalysisConfig(
            Set.of(BASE_MODEL_HIERARCHY), BASE_MODEL_FQCN, List.of(), List.of());
        List<DetectionResult> results = analyze(List.of(baseModel, dto), config);

        DetectionResult r = results.stream()
            .filter(x -> "com.example.dto.SafeDto".equals(x.dtoFqcn()))
            .findFirst().orElseThrow(() -> new AssertionError("SafeDto 미검출"));
        assertEquals(SAFE, r.riskLevel());
    }

    @Test
    void baseModelHierarchy_abstractClass_flagged() throws Exception {
        File baseModel = baseModelStub();
        File dto = write("com/example/dto/AbstractDto.java", """
            package com.example.dto;
            import com.example.BaseModel;
            public abstract class AbstractDto extends BaseModel {
                public AbstractDto(String s) {}
            }
            """);

        AnalysisConfig config = new AnalysisConfig(
            Set.of(BASE_MODEL_HIERARCHY), BASE_MODEL_FQCN, List.of(), List.of());
        List<DetectionResult> results = analyze(List.of(baseModel, dto), config);

        DetectionResult r = results.stream()
            .filter(x -> "com.example.dto.AbstractDto".equals(x.dtoFqcn()))
            .findFirst().orElseThrow(() -> new AssertionError("AbstractDto 미검출"));
        assertTrue(r.abstractFlag());
        assertTrue(r.action().contains("추상클래스"));
    }

    // ── CACHE_REACHABLE 범위 ──────────────────────────────────────────────

    @Test
    void cacheReachable_r2_detected() throws Exception {
        File cacheable = cacheableStub();
        File dto = write("com/example/dto/R2Dto.java", """
            package com.example.dto;
            public class R2Dto {
                public R2Dto(String s) {}
            }
            """);
        File service = write("com/example/service/MyService.java", """
            package com.example.service;
            import com.example.dto.R2Dto;
            import org.springframework.cache.annotation.Cacheable;
            public class MyService {
                @Cacheable
                public R2Dto getDto(String key) { return null; }
            }
            """);

        AnalysisConfig config = new AnalysisConfig(
            Set.of(CACHE_REACHABLE), BASE_MODEL_FQCN, List.of(), List.of());
        List<DetectionResult> results = analyze(List.of(cacheable, dto, service), config);

        DetectionResult r = results.stream()
            .filter(x -> "com.example.dto.R2Dto".equals(x.dtoFqcn()))
            .findFirst().orElseThrow(() -> new AssertionError("R2Dto 미검출. 결과: " + results));
        assertEquals(R2, r.riskLevel());
        assertTrue(r.detectedBy().contains(CACHE_REACHABLE));
    }

    // ── 두 범위 모두 해당 — 레코드 1건, detectedBy=BOTH ─────────────────────

    @Test
    void bothScopes_singleRecord_withBothDetectedBy() throws Exception {
        File cacheable = cacheableStub();
        File baseModel = baseModelStub();
        File dto = write("com/example/dto/BothDto.java", """
            package com.example.dto;
            import com.example.BaseModel;
            public class BothDto extends BaseModel {
                public BothDto(String s) {}
            }
            """);
        File service = write("com/example/service/MyService.java", """
            package com.example.service;
            import com.example.dto.BothDto;
            import org.springframework.cache.annotation.Cacheable;
            public class MyService {
                @Cacheable
                public BothDto getDto(String key) { return null; }
            }
            """);

        AnalysisConfig config = new AnalysisConfig(
            Set.of(CACHE_REACHABLE, BASE_MODEL_HIERARCHY), BASE_MODEL_FQCN, List.of(), List.of());
        List<DetectionResult> results = analyze(
            List.of(cacheable, baseModel, dto, service), config);

        long count = results.stream()
            .filter(x -> "com.example.dto.BothDto".equals(x.dtoFqcn())).count();
        assertEquals(1, count, "중복 없이 레코드 1건이어야 합니다");

        DetectionResult r = results.stream()
            .filter(x -> "com.example.dto.BothDto".equals(x.dtoFqcn()))
            .findFirst().orElseThrow();
        assertTrue(r.detectedBy().containsAll(Set.of(CACHE_REACHABLE, BASE_MODEL_HIERARCHY)));
    }

    // ── excludePackages 필터 ──────────────────────────────────────────────

    @Test
    void excludePackages_filtered() throws Exception {
        File baseModel = baseModelStub();
        File dto = write("com/example/excluded/ExcludedDto.java", """
            package com.example.excluded;
            import com.example.BaseModel;
            public class ExcludedDto extends BaseModel {
                public ExcludedDto(String s) {}
            }
            """);

        AnalysisConfig config = new AnalysisConfig(
            Set.of(BASE_MODEL_HIERARCHY), BASE_MODEL_FQCN,
            List.of(), List.of("com.example.excluded"));
        List<DetectionResult> results = analyze(List.of(baseModel, dto), config);

        assertFalse(results.stream().anyMatch(x -> x.dtoFqcn().contains("ExcludedDto")),
            "제외 패키지 클래스가 결과에 포함되지 않아야 합니다");
    }

    // ── basePackages 필터 ─────────────────────────────────────────────────

    @Test
    void basePackages_outsidePackage_filtered() throws Exception {
        File baseModel = baseModelStub();
        File dto = write("com/example/dto/OutDto.java", """
            package com.example.dto;
            import com.example.BaseModel;
            public class OutDto extends BaseModel {
                public OutDto(String s) {}
            }
            """);

        AnalysisConfig config = new AnalysisConfig(
            Set.of(BASE_MODEL_HIERARCHY), BASE_MODEL_FQCN,
            List.of("com.example.inside"), List.of());
        List<DetectionResult> results = analyze(List.of(baseModel, dto), config);

        assertFalse(results.stream().anyMatch(x -> x.dtoFqcn().contains("OutDto")),
            "basePackages 범위 밖 클래스는 결과에 포함되지 않아야 합니다");
    }

    // ── noopLog ──────────────────────────────────────────────────────────

    private static Log noopLog() {
        return new Log() {
            public boolean isDebugEnabled() { return false; }
            public void debug(CharSequence msg) {}
            public void debug(CharSequence msg, Throwable t) {}
            public void debug(Throwable t) {}
            public boolean isInfoEnabled() { return true; }
            public void info(CharSequence msg) {}
            public void info(CharSequence msg, Throwable t) {}
            public void info(Throwable t) {}
            public boolean isWarnEnabled() { return true; }
            public void warn(CharSequence msg) {}
            public void warn(CharSequence msg, Throwable t) {}
            public void warn(Throwable t) {}
            public boolean isErrorEnabled() { return true; }
            public void error(CharSequence msg) {}
            public void error(CharSequence msg, Throwable t) {}
            public void error(Throwable t) {}
        };
    }
}
