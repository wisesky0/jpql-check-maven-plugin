package io.github.wisesky0.jpqlcheck;

import com.sun.source.tree.*;
import com.sun.source.util.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

import io.github.wisesky0.jpqlcheck.join.JoinChainAnalyzer;

/**
 * QueryDSL Expressions.*Template(..) 호출을 정적 분석하여
 * Hibernate 6.6.49.Final TypecheckUtil 런타임 오류 패턴을 감지한다.
 *
 * PRD v3.2 5장 판정 알고리즘 구현:
 * - Pass 0: 메서드 본문 내 위험 템플릿 변수 집합 구성 (D-08)
 * - Pass 1: 템플릿 호출 지점의 오류 1 검사(D-01, D-02, E-01, E-02) 및
 *           직접 비교 지점의 오류 2 검사(D-03~D-07)
 * - Pass 2: 위험 템플릿 변수의 비교 사용 지점 검사 (D-08)
 *
 * TreePathScanner가 소스 순서대로 방문하므로 Pass 0(변수 수집)과
 * Pass 1/2(사용 지점 검사)는 단일 순회로 함께 수행된다.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedOptions({"jpql.check.reportDir", "jpql.check.formats", "jpql.check.failOnError"})
public class JpqlFunctionTypeProcessor extends AbstractProcessor {

    /** 오류 2 감지 대상 비교 연산 (PRD D-03) */
    private static final Set<String> COMPARISON_OPS = Set.of(
        "goe", "gt", "loe", "lt", "eq", "ne", "between", "notBetween", "in", "notIn");

    private final List<Finding> findings = new ArrayList<>();
    private Trees trees;
    private boolean taskListenerRegistered;

    /**
     * 어노테이션 프로세싱 라운드는 javac 타입 속성 분석(attribution) 이전에 실행되어
     * 표현식의 TypeMirror를 안정적으로 얻을 수 없다 (특히 오버로드 비교 메서드의 인자).
     * 따라서 TaskListener를 등록하여 각 클래스의 ANALYZE 완료 후(타입 확정 상태)에 스캔한다.
     */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        trees = Trees.instance(processingEnv);
        try {
            JavacTask task = JavacTask.instance(processingEnv);
            task.addTaskListener(new TaskListener() {
                @Override
                public void finished(TaskEvent e) {
                    if (e.getKind() == TaskEvent.Kind.ANALYZE) {
                        TypeElement type = e.getTypeElement();
                        TreePath path = type != null ? trees.getPath(type) : null;
                        if (path == null && e.getCompilationUnit() != null) {
                            path = new TreePath(e.getCompilationUnit());
                        }
                        if (path != null) {
                            new TemplateScanner().scan(path, null);
                            new JoinChainAnalyzer(trees, processingEnv, findings, isFailOnError()).scan(path, null);
                        }
                    } else if (e.getKind() == TaskEvent.Kind.COMPILATION) {
                        writeReports();
                    }
                }
            });
            taskListenerRegistered = true;
        } catch (RuntimeException ex) {
            taskListenerRegistered = false;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (taskListenerRegistered) return false;
        // 폴백 경로: TaskListener를 등록할 수 없는 환경에서는 라운드 기반으로 스캔한다.
        // (타입 미확정으로 일부 비교 지점 감지가 누락될 수 있음)
        if (roundEnv.processingOver()) {
            writeReports();
        } else {
            for (Element element : roundEnv.getRootElements()) {
                TreePath path = trees.getPath(element);
                if (path == null) continue;
                new TemplateScanner().scan(path, null);
                new JoinChainAnalyzer(trees, processingEnv, findings, isFailOnError()).scan(path, null);
            }
        }
        return false;
    }

    boolean isFailOnError() {
        return !"false".equalsIgnoreCase(
            processingEnv.getOptions().getOrDefault("jpql.check.failOnError", "true"));
    }

    private void writeReports() {
        String reportDir = processingEnv.getOptions().getOrDefault(
            "jpql.check.reportDir", "target/jpql-check");
        String formats = processingEnv.getOptions().getOrDefault(
            "jpql.check.formats", "json");
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(reportDir);
            for (String format : formats.split(",")) {
                switch (format.trim().toLowerCase(Locale.ROOT)) {
                    case "json" -> new JsonReporter(dir).write(findings);
                    case "html" -> new HtmlReporter(dir).write(findings);
                    case "sarif" -> new SarifReporter(dir).write(findings);
                    default -> { }
                }
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.NOTE, "리포트 파일 생성 실패: " + e.getMessage());
        }
    }

    /** 피연산자 분류 (PRD 3.1 핵심 판정 원리) */
    private enum ExprKind { PATH, CONSTANT, OTHER }

    /** Pass 0에서 수집한 위험 템플릿 변수 (PRD D-08) */
    private record RiskyVar(String template, String functionName, long declLine) {}

    /** 비교 피연산자의 위험 템플릿 참조 정보. declLine < 0 이면 직접 호출(D-03), 이상이면 변수 경유(D-08) */
    private record RiskyRef(String template, String functionName, long declLine) {}

    private class TemplateScanner extends TreePathScanner<Void, Void> {

        private final Deque<Map<String, RiskyVar>> riskyVarScopes = new ArrayDeque<>();
        /** I-03 WARN 중복 보고 방지용 (동일 노드가 비교 검사에서 재방문될 수 있음) */
        private final Set<Tree> warnedTemplateCalls = new HashSet<>();

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            riskyVarScopes.push(new HashMap<>());
            try {
                return super.visitMethod(node, unused);
            } finally {
                riskyVarScopes.pop();
            }
        }

        // --- Pass 0: 위험 템플릿 변수 수집 ---

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            registerIfRiskyTemplate(node.getName().toString(), node.getInitializer());
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void unused) {
            if (node.getVariable() instanceof IdentifierTree id) {
                registerIfRiskyTemplate(id.getName().toString(), node.getExpression());
            }
            return super.visitAssignment(node, unused);
        }

        private void registerIfRiskyTemplate(String varName, ExpressionTree init) {
            if (riskyVarScopes.isEmpty() || init == null) return;
            ExpressionTree stripped = strip(init);
            if (!(stripped instanceof MethodInvocationTree mit) || !isTemplateFactoryCall(mit)) return;
            String tmpl = resolveTemplateString(mit);
            if (tmpl == null) return; // I-03 WARN은 visitMethodInvocation에서 보고
            List<TemplateParser.FunctionCall> unregistered = TemplateParser.parse(tmpl).unregisteredCalls();
            if (unregistered.isEmpty()) return;
            long line = lineOf(stripped);
            riskyVarScopes.peek().put(varName,
                new RiskyVar(tmpl, unregistered.get(0).name(), line));
        }

        // --- Pass 1/2: 호출 지점 검사 ---

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (isTemplateFactoryCall(node)) {
                checkTemplateCall(node);
            } else if (isComparisonCall(node)) {
                checkComparison(node);
            }
            return super.visitMethodInvocation(node, unused);
        }

        /** 오류 1 검사 (D-01, D-02, E-01) + 비상수 템플릿 WARN (I-03) */
        private void checkTemplateCall(MethodInvocationTree node) {
            String tmpl = resolveTemplateString(node);
            if (tmpl == null) {
                if (warnedTemplateCalls.add(node)) {
                    report(node, "I-03", "WARNING", null, null, node.toString(),
                        TypeNormalizer.UNRESOLVED, null, -1,
                        "템플릿 문자열이 컴파일 타임 상수가 아니어서 판정할 수 없습니다",
                        "템플릿 문자열을 리터럴 또는 static final 상수로 선언하십시오");
                }
                return;
            }
            List<? extends ExpressionTree> bindings = bindingArgs(node);
            TemplateParser.ParsedTemplate parsed = TemplateParser.parse(tmpl);

            for (TemplateParser.FunctionCall call : parsed.registeredCalls()) {
                FunctionEntry entry = FunctionCatalog.lookup(call.name()).orElse(null);
                if (entry == null) continue;
                List<String> args = call.args();
                for (int pos = 0; pos < args.size(); pos++) {
                    String argText = args.get(pos);
                    if (TemplateParser.containsCast(argText)) continue;              // E-01
                    int placeholder = TemplateParser.barePlaceholderIndex(argText);
                    if (placeholder < 0 || placeholder >= bindings.size()) continue; // 보수적 미감지
                    ExpressionTree binding = strip(bindings.get(placeholder));
                    if (classify(binding) != ExprKind.PATH) continue;                // D-02

                    TypeCategory expected = expectedCategory(entry, pos);
                    if (expected == null || expected == TypeCategory.GENERAL) continue;

                    String javaType = normalizedTypeOf(binding);
                    TypeCategory actual = TypeCategory.fromJavaType(javaType);
                    if (actual == TypeCategory.UNKNOWN) {
                        report(node, "D-01", "WARNING", tmpl, call.name(), binding.toString(),
                            javaType, expected.toString(), -1,
                            "Path 타입을 정적으로 결정할 수 없습니다 (UNRESOLVED)",
                            castRecommendation(call.name(), placeholder, expected));
                        continue;
                    }
                    if (!isCompatible(actual, expected)) {
                        report(node, "D-01", "ERROR", tmpl, call.name(), binding.toString(),
                            javaType, expected.toString(), -1,
                            "등록 함수 " + call.name() + "의 인자 #" + pos + "는 " + expected
                                + " 카테고리 요구, " + actual + "(" + javaType + ") Path가 바인딩됨"
                                + " — Hibernate TypecheckUtil 런타임 예외 발생 (PRD 오류 1)",
                            castRecommendation(call.name(), placeholder, expected));
                    }
                }
            }
        }

        /** 오류 2 검사 — 직접 비교(D-03~D-07) 및 변수 경유(D-08) */
        private void checkComparison(MethodInvocationTree node) {
            if (!(node.getMethodSelect() instanceof MemberSelectTree ms)) return;
            ExpressionTree receiver = strip(ms.getExpression());

            RiskyRef receiverRisk = riskyTemplateRef(receiver);
            if (receiverRisk != null) {
                for (ExpressionTree arg : node.getArguments()) {
                    reportIfPathComparison(node, receiverRisk, strip(arg));
                }
                return;
            }
            for (ExpressionTree arg : node.getArguments()) {
                RiskyRef argRisk = riskyTemplateRef(strip(arg));
                if (argRisk != null) {
                    reportIfPathComparison(node, argRisk, receiver); // D-05: 템플릿이 우변
                    return;
                }
            }
        }

        private void reportIfPathComparison(MethodInvocationTree node, RiskyRef risk, ExpressionTree target) {
            if (classify(target) != ExprKind.PATH) return; // D-04: Constant 계열 미감지
            boolean viaVariable = risk.declLine() >= 0;
            String ruleId = viaVariable ? "D-08" : "D-03";
            report(node, ruleId, "ERROR", risk.template(), risk.functionName(), target.toString(),
                normalizedTypeOf(target), "java.lang.Object(미등록 함수 반환 추론)",
                viaVariable ? risk.declLine() : -1,
                "미등록 함수 " + risk.functionName() + "의 반환 타입이 Object로 추론되어 Path 계열("
                    + target + ")과의 비교에서 Hibernate TypecheckUtil 런타임 예외 발생 (PRD 오류 2)"
                    + (viaVariable ? " — 템플릿 변수 대입 라인: " + risk.declLine() : ""),
                "function('" + risk.functionName().toUpperCase(Locale.ROOT)
                    + "', ...) 표준 구문으로 우회하십시오 (PRD E-02)");
        }

        /** 표현식이 위험 템플릿(직접 호출 또는 위험 변수 참조)인지 판정한다. */
        private RiskyRef riskyTemplateRef(ExpressionTree expr) {
            if (expr == null) return null;
            if (expr instanceof MethodInvocationTree mit && isTemplateFactoryCall(mit)) {
                String tmpl = resolveTemplateString(mit);
                if (tmpl == null) return null;
                List<TemplateParser.FunctionCall> unregistered = TemplateParser.parse(tmpl).unregisteredCalls();
                if (unregistered.isEmpty()) return null;
                return new RiskyRef(tmpl, unregistered.get(0).name(), -1);
            }
            if (expr instanceof IdentifierTree id) {
                String name = id.getName().toString();
                for (Map<String, RiskyVar> scope : riskyVarScopes) {
                    RiskyVar v = scope.get(name);
                    if (v != null) return new RiskyRef(v.template(), v.functionName(), v.declLine());
                }
            }
            return null;
        }

        // --- 분류/해석 유틸 ---

        private boolean isTemplateFactoryCall(MethodInvocationTree node) {
            String name = methodName(node);
            return name != null && (name.endsWith("Template") || name.equals("template"));
        }

        private boolean isComparisonCall(MethodInvocationTree node) {
            String name = methodName(node);
            return name != null && COMPARISON_OPS.contains(name)
                && node.getMethodSelect() instanceof MemberSelectTree;
        }

        private String methodName(MethodInvocationTree node) {
            ExpressionTree select = node.getMethodSelect();
            if (select instanceof MemberSelectTree ms) return ms.getIdentifier().toString();
            if (select instanceof IdentifierTree id) return id.getName().toString();
            return null;
        }

        /**
         * 템플릿 문자열 해석: 첫 String 리터럴 인자 또는 컴파일 타임 상수(static final).
         * 해석 불가 시 null (I-03).
         */
        private String resolveTemplateString(MethodInvocationTree node) {
            for (ExpressionTree arg : node.getArguments()) {
                ExpressionTree stripped = strip(arg);
                if (stripped instanceof LiteralTree lit && lit.getValue() instanceof String s) {
                    return s;
                }
                if (stripped instanceof IdentifierTree || stripped instanceof MemberSelectTree) {
                    TreePath p = pathOf(stripped);
                    if (p != null) {
                        Element el = trees.getElement(p);
                        if (el instanceof javax.lang.model.element.VariableElement ve
                            && ve.getConstantValue() instanceof String s) {
                            return s;
                        }
                    }
                    // String 타입 비상수 인자가 템플릿 자리인 경우 → 해석 불가
                    TypeMirror tm = typeOf(stripped);
                    if (tm != null && "java.lang.String".equals(tm.toString())) return null;
                }
            }
            return null;
        }

        /** 템플릿 문자열 인자 이후의 바인딩 인자 목록 */
        private List<? extends ExpressionTree> bindingArgs(MethodInvocationTree node) {
            List<? extends ExpressionTree> args = node.getArguments();
            for (int i = 0; i < args.size(); i++) {
                ExpressionTree stripped = strip(args.get(i));
                if (stripped instanceof LiteralTree lit && lit.getValue() instanceof String) {
                    return args.subList(i + 1, args.size());
                }
                TypeMirror tm = typeOf(stripped);
                if (tm != null && "java.lang.String".equals(tm.toString())) {
                    return args.subList(i + 1, args.size());
                }
            }
            return List.of();
        }

        /** PRD 3.1: Path 계열 / Constant 계열 / 기타 분류 */
        private ExprKind classify(ExpressionTree expr) {
            if (expr == null) return ExprKind.OTHER;
            if (expr instanceof LiteralTree) return ExprKind.CONSTANT;
            TypeMirror tm = typeOf(expr);
            if (tm == null) return ExprKind.OTHER;
            if (tm.getKind().isPrimitive()) return ExprKind.CONSTANT;
            if (tm.getKind() != TypeKind.DECLARED) return ExprKind.OTHER;

            String typeStr = processingEnv.getTypeUtils().erasure(tm).toString();
            if (isSubtypeOf(tm, "com.querydsl.core.types.Constant")) return ExprKind.CONSTANT;
            if (isSubtypeOf(tm, "com.querydsl.core.types.Path")) return ExprKind.PATH;
            // 폴백 휴리스틱 (Path/Constant 타입 엘리먼트를 해석할 수 없는 환경)
            if (typeStr.startsWith("com.querydsl") && typeStr.endsWith("Path")) return ExprKind.PATH;
            if (typeStr.startsWith("com.querydsl") && typeStr.contains("Constant")) return ExprKind.CONSTANT;
            // java.* 타입의 일반 값은 바인드 파라미터로 렌더링됨 (PRD 3.1)
            if (typeStr.startsWith("java.")) return ExprKind.CONSTANT;
            return ExprKind.OTHER;
        }

        private boolean isSubtypeOf(TypeMirror tm, String fqcn) {
            TypeElement target = processingEnv.getElementUtils().getTypeElement(fqcn);
            if (target == null) return false;
            javax.lang.model.util.Types types = processingEnv.getTypeUtils();
            return types.isSubtype(types.erasure(tm), types.erasure(target.asType()));
        }

        private TypeMirror typeOf(ExpressionTree expr) {
            TreePath p = pathOf(expr);
            return p == null ? null : trees.getTypeMirror(p);
        }

        private TreePath pathOf(Tree tree) {
            CompilationUnitTree cu = getCurrentPath().getCompilationUnit();
            return TreePath.getPath(cu, tree);
        }

        private String normalizedTypeOf(ExpressionTree expr) {
            TypeMirror tm = typeOf(expr);
            if (tm == null) return TypeNormalizer.UNRESOLVED;
            String typeStr = tm.toString();
            if (typeStr.startsWith("com.querydsl")) {
                return TypeNormalizer.normalizeFromSignature(typeStr);
            }
            return typeStr;
        }

        private TypeCategory expectedCategory(FunctionEntry entry, int argPos) {
            if (entry.argCategories() != null && argPos < entry.argCategories().size()) {
                return entry.argCategories().get(argPos);
            }
            return entry.category();
        }

        private boolean isCompatible(TypeCategory actual, TypeCategory expected) {
            return actual == expected;
        }

        private String castRecommendation(String fn, int placeholder, TypeCategory expected) {
            String hqlType = switch (expected) {
                case NUMERIC -> "long";
                case STRING -> "string";
                case DATETIME -> "timestamp";
                default -> "string";
            };
            return "cast 적용 — \"" + fn.toUpperCase(Locale.ROOT) + "(cast({" + placeholder
                + "} as " + hqlType + "))\" 형식으로 우회하십시오 (PRD E-01)";
        }

        private long lineOf(Tree tree) {
            CompilationUnitTree cu = getCurrentPath().getCompilationUnit();
            long pos = trees.getSourcePositions().getStartPosition(cu, tree);
            return cu.getLineMap().getLineNumber(pos);
        }

        // --- 보고 ---

        private void report(MethodInvocationTree node, String ruleId, String severity,
                            String templateString, String functionName, String offendingExpr,
                            String inferredType, String expectedType, long relatedLine,
                            String reason, String recommendation) {
            CompilationUnitTree cu = getCurrentPath().getCompilationUnit();
            SourcePositions positions = trees.getSourcePositions();
            long startPos = positions.getStartPosition(cu, node);
            long line = cu.getLineMap().getLineNumber(startPos);
            long col = cu.getLineMap().getColumnNumber(startPos);
            String fileName = cu.getSourceFile().getName();
            if (fileName.contains("/")) fileName = fileName.substring(fileName.lastIndexOf('/') + 1);

            StringBuilder msg = new StringBuilder();
            msg.append("[").append(ruleId).append("] ").append(fileName)
               .append(":[").append(line).append(",").append(col).append("] ").append(reason);
            if (templateString != null) msg.append("\n  템플릿     : \"").append(templateString).append("\"");
            if (functionName != null) msg.append("\n  함수       : ").append(functionName);
            if (offendingExpr != null) msg.append("\n  대상 표현식 : ").append(offendingExpr)
                                          .append(" (").append(inferredType).append(")");
            if (expectedType != null) msg.append("\n  기대 타입   : ").append(expectedType);
            if (relatedLine >= 0) msg.append("\n  관련 라인   : ").append(relatedLine).append(" (템플릿 대입 지점)");
            if (recommendation != null) msg.append("\n  권장 조치   : ").append(recommendation);

            // jpql.check.failOnError=false 이면 ERROR도 WARNING으로 출력하여
            // 컴파일을 중지시키지 않는다. 리포트의 Finding 심각도는 ERROR로 유지된다.
            Diagnostic.Kind kind = "ERROR".equals(severity) && isFailOnError()
                ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
            Element element = trees.getElement(getCurrentPath());
            if (element != null) {
                processingEnv.getMessager().printMessage(kind, msg.toString(), element);
            } else {
                processingEnv.getMessager().printMessage(kind, msg.toString());
            }

            findings.add(new Finding(ruleId, severity, fileName, line, col,
                templateString, functionName, offendingExpr, inferredType, expectedType,
                relatedLine, reason, recommendation));
        }

        private ExpressionTree strip(ExpressionTree expr) {
            while (expr instanceof ParenthesizedTree p) expr = p.getExpression();
            return expr;
        }
    }
}
