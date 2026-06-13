package io.github.wisesky0.jacksoncheck;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.wisesky0.jacksoncheck.model.*;
import io.github.wisesky0.jacksoncheck.scan.ClassInfoScanner;
import org.apache.maven.plugin.logging.Log;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class AnalysisEngine {
    private final Log log;

    public AnalysisEngine(Log log) { this.log = log; }

    public List<DetectionResult> analyze(
            JavaCompiler compiler,
            List<File> sourceFiles,
            List<String> classpathElements,
            AnalysisConfig config) throws Exception {

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);

        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sourceFiles);
        String classpath = String.join(File.pathSeparator, classpathElements);
        List<String> options = Arrays.asList(
            "-classpath", classpath,
            "-proc:none",     // no annotation processing
            "-implicit:none"  // suppress implicit compilation
        );

        JavacTask task = (JavacTask) compiler.getTask(null, fm, diagnostics, options, null, units);
        Trees trees = Trees.instance(task);

        // Parse and analyze (type attribution)
        Iterable<? extends CompilationUnitTree> compilationUnits = task.parse();
        List<CompilationUnitTree> cuList = new ArrayList<>();
        compilationUnits.forEach(cuList::add);
        task.analyze();

        // Scan all classes
        List<ClassInfo> allClasses = new ArrayList<>();
        ClassInfoScanner scanner = new ClassInfoScanner(trees);
        for (CompilationUnitTree cu : cuList) {
            scanner.scan(new TreePath(cu), allClasses);
        }
        log.info("스캔 완료: " + allClasses.size() + "개 타입");

        // Post-process: resolve BaseModel inheritance
        Map<String, ClassInfo> byFqcn = new LinkedHashMap<>();
        allClasses.forEach(c -> byFqcn.put(c.fqcn, c));
        resolveBaseModelHierarchy(byFqcn, config.baseModelFqcn());

        // Build results
        return buildResults(allClasses, byFqcn, config);
    }

    private void resolveBaseModelHierarchy(Map<String, ClassInfo> byFqcn, String baseModelFqcn) {
        for (ClassInfo info : byFqcn.values()) {
            if (isBaseModelDescendant(info, byFqcn, baseModelFqcn, new HashSet<>())) {
                info.extendsBaseModel = true;
            }
        }
    }

    private boolean isBaseModelDescendant(ClassInfo info, Map<String, ClassInfo> byFqcn,
                                          String baseModelFqcn, Set<String> visited) {
        if (info == null || info.fqcn == null || visited.contains(info.fqcn)) return false;
        visited.add(info.fqcn);
        if (info.superFqcn == null) return false;
        if (info.superFqcn.equals(baseModelFqcn)) return true;
        if (info.superFqcn.contains(simpleBaseName(baseModelFqcn))) return true; // heuristic
        ClassInfo superInfo = byFqcn.get(info.superFqcn);
        return isBaseModelDescendant(superInfo, byFqcn, baseModelFqcn, visited);
    }

    private String simpleBaseName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private List<DetectionResult> buildResults(List<ClassInfo> allClasses,
                                                Map<String, ClassInfo> byFqcn,
                                                AnalysisConfig config) {
        List<DetectionResult> results = new ArrayList<>();

        // Collect CACHE_REACHABLE types (1st level: return + param types of cache methods)
        Set<String> cacheReachable = new LinkedHashSet<>();
        if (config.scopes().contains(DetectionScope.CACHE_REACHABLE)) {
            for (ClassInfo info : allClasses) {
                cacheReachable.addAll(info.cacheReturnTypes);
                cacheReachable.addAll(info.cacheParamTypes);
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        for (ClassInfo info : allClasses) {
            if (info.fqcn == null || info.fqcn.isEmpty()) continue;
            if (!matchesPackageFilter(info.fqcn, config)) continue;

            Set<DetectionScope> detectedBy = new LinkedHashSet<>();
            if (config.scopes().contains(DetectionScope.BASE_MODEL_HIERARCHY) && info.extendsBaseModel) {
                detectedBy.add(DetectionScope.BASE_MODEL_HIERARCHY);
            }
            if (config.scopes().contains(DetectionScope.CACHE_REACHABLE) && cacheReachable.contains(info.fqcn)) {
                detectedBy.add(DetectionScope.CACHE_REACHABLE);
            }

            if (detectedBy.isEmpty()) continue;
            if (!seen.add(info.fqcn)) continue; // de-duplicate

            RiskLevel risk = RiskJudge.judge(info);
            String action = info.isAbstract ? "REPORT_ONLY(추상클래스)" : "REPORT_ONLY";
            String inheritanceChain = info.extendsBaseModel
                ? (info.superFqcn != null ? info.fqcn + " → " + info.superFqcn : info.fqcn)
                : null;

            results.add(new DetectionResult(
                info.fqcn, risk, info.constructors.summary(),
                detectedBy, null, inheritanceChain,
                info.extendsBaseModel, info.isAbstract,
                action, info.sourceFile, info.line));
        }

        results.sort(Comparator.comparingInt(r -> r.riskLevel().ordinal()));
        return results;
    }

    private boolean matchesPackageFilter(String fqcn, AnalysisConfig config) {
        if (!config.basePackages().isEmpty()) {
            boolean any = config.basePackages().stream().anyMatch(p -> fqcn.startsWith(p));
            if (!any) return false;
        }
        if (!config.excludePackages().isEmpty()) {
            boolean excl = config.excludePackages().stream().anyMatch(p -> fqcn.startsWith(p));
            if (excl) return false;
        }
        return true;
    }
}
