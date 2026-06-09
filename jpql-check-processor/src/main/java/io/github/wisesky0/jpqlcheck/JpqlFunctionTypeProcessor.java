package io.github.wisesky0.jpqlcheck;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.annotation.processing.*;
import javax.lang.model.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.*;
import java.util.*;

@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class JpqlFunctionTypeProcessor extends AbstractProcessor {

    private final List<Finding> findings = new ArrayList<>();
    private Trees trees;

    // Map from QueryDSL method name to function name
    private static final Map<String, String> METHOD_TO_FUNCTION = Map.of(
        "abs", "abs", "sqrt", "sqrt", "length", "length",
        "lower", "lower", "upper", "upper", "trim", "trim",
        "floor", "floor", "ceiling", "ceiling", "round", "round",
        "mod", "mod"
    );

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        trees = Trees.instance(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            writeReports();
        } else {
            analyzeRound(roundEnv);
        }
        return false;
    }

    private void analyzeRound(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getRootElements()) {
            TreePath path = trees.getPath(element);
            if (path == null) continue;
            new FunctionCallScanner().scan(path, trees);
        }
    }

    private void writeReports() {
        String reportDir = processingEnv.getOptions().getOrDefault(
            "jpql.check.reportDir", "target/jpql-check");
        try {
            java.io.File dir = new java.io.File(reportDir);
            dir.mkdirs();
            new JsonReporter(dir.toPath()).write(findings);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.NOTE, "리포트 파일 생성 실패: " + e.getMessage());
        }
    }

    private class FunctionCallScanner extends TreePathScanner<Void, Trees> {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Trees trees) {
            checkMethodInvocation(node, getCurrentPath(), trees);
            return super.visitMethodInvocation(node, trees);
        }

        private void checkMethodInvocation(MethodInvocationTree node, TreePath path, Trees trees) {
            if (isExpressionsOperation(node)) {
                checkExpressionsOperation(node, path, trees);
                return;
            }
            if (isBuiltinMethod(node)) {
                checkBuiltinMethod(node, path, trees);
            }
        }

        private boolean isExpressionsOperation(MethodInvocationTree node) {
            ExpressionTree select = node.getMethodSelect();
            if (select instanceof MemberSelectTree ms) {
                String methodName = ms.getIdentifier().toString();
                return methodName.endsWith("Operation") || methodName.endsWith("Template");
            }
            return false;
        }

        private boolean isBuiltinMethod(MethodInvocationTree node) {
            ExpressionTree select = node.getMethodSelect();
            if (select instanceof MemberSelectTree ms) {
                String methodName = ms.getIdentifier().toString();
                return METHOD_TO_FUNCTION.containsKey(methodName);
            }
            return false;
        }

        private void checkExpressionsOperation(MethodInvocationTree node, TreePath path, Trees trees) {
            List<? extends ExpressionTree> args = node.getArguments();
            if (args.size() < 2) return;

            String functionName = extractFunctionName(args);
            if (functionName == null) return;

            Optional<FunctionEntry> entryOpt = FunctionCatalog.lookup(functionName);
            if (entryOpt.isEmpty()) return;
            FunctionEntry entry = entryOpt.get();

            int startIdx = args.size() >= 3 ? 2 : 1;
            checkArguments(functionName, entry, args, startIdx, node, path, trees);
        }

        private void checkBuiltinMethod(MethodInvocationTree node, TreePath path, Trees trees) {
            ExpressionTree select = node.getMethodSelect();
            if (!(select instanceof MemberSelectTree ms)) return;

            String methodName = ms.getIdentifier().toString();
            String functionName = METHOD_TO_FUNCTION.get(methodName);
            if (functionName == null) return;

            Optional<FunctionEntry> entryOpt = FunctionCatalog.lookup(functionName);
            if (entryOpt.isEmpty()) return;
            FunctionEntry entry = entryOpt.get();

            ExpressionTree receiver = ms.getExpression();
            List<ExpressionTree> effectiveArgs = new ArrayList<>();
            effectiveArgs.add(receiver);
            effectiveArgs.addAll(node.getArguments());

            checkArguments(functionName, entry, effectiveArgs, 0, node, path, trees);
        }

        private String extractFunctionName(List<? extends ExpressionTree> args) {
            if (args.size() < 2) return null;
            ExpressionTree opArg = args.get(1);
            String opStr = opArg.toString();
            Map<String, String> opMap = Map.of(
                "ABS", "abs", "SQRT", "sqrt", "MOD", "mod",
                "FLOOR", "floor", "CEILING", "ceiling", "SIGN", "sign",
                "EXP", "exp", "LN", "ln", "POWER", "power", "ROUND", "round"
            );
            for (Map.Entry<String, String> e : opMap.entrySet()) {
                if (opStr.toUpperCase().contains(e.getKey())) return e.getValue();
            }
            if (args.size() >= 2) {
                ExpressionTree tmplArg = args.get(1);
                String tmpl = tmplArg.toString().toLowerCase();
                for (String fn : List.of("abs", "sqrt", "mod", "length", "lower", "upper",
                                         "extract", "floor", "ceiling", "round", "power")) {
                    if (tmpl.contains(fn + "(") || tmpl.contains(fn + " ")) return fn;
                    if (tmpl.startsWith("\"extract")) return "extract";
                }
            }
            return null;
        }

        private void checkArguments(String functionName, FunctionEntry entry,
                                    List<? extends ExpressionTree> args, int startIdx,
                                    MethodInvocationTree node, TreePath path, Trees trees) {
            int actualArgCount = args.size() - startIdx;

            if (actualArgCount < entry.minArgs() ||
                (entry.maxArgs() != -1 && actualArgCount > entry.maxArgs())) {
                reportFinding(node, path, trees, functionName, entry, -1,
                    String.valueOf(actualArgCount), entry.minArgs() + " ~ " + entry.maxArgs(),
                    "ERROR", "인자 개수 불일치: " + entry.minArgs() + "개 필요, " + actualArgCount + "개 제공");
                return;
            }

            List<TypeCategory> expectedCategories = entry.argCategories();
            for (int i = 0; i < actualArgCount; i++) {
                int argIdx = startIdx + i;
                if (argIdx >= args.size()) break;
                ExpressionTree arg = args.get(argIdx);

                TypeMirror argType = trees.getTypeMirror(TreePath.getPath(path.getCompilationUnit(), arg));
                if (argType == null) continue;

                String normalizedType = normalizeTypeMirror(argType);
                TypeCategory argCategory = TypeCategory.fromJavaType(normalizedType);

                TypeCategory expected = null;
                if (expectedCategories != null && i < expectedCategories.size()) {
                    expected = expectedCategories.get(i);
                } else if (entry.category() != TypeCategory.GENERAL) {
                    expected = entry.category();
                }

                if (expected == null || expected == TypeCategory.GENERAL) continue;

                if (TypeNormalizer.isUnresolved(normalizedType) || argCategory == TypeCategory.UNKNOWN) {
                    reportFinding(node, path, trees, functionName, entry, i,
                        normalizedType, expected.toString(),
                        "WARNING", "UNRESOLVED - 타입을 정적으로 결정할 수 없습니다");
                    continue;
                }

                if (!isCompatible(argCategory, expected)) {
                    if ("mod".equals(functionName) && argCategory == TypeCategory.NUMERIC) {
                        reportFinding(node, path, trees, functionName, entry, i,
                            normalizedType, expected.toString(),
                            "WARNING", "정밀도 손실 가능: mod는 정수 인자 권장, " + normalizedType + " 사용");
                    } else {
                        reportFinding(node, path, trees, functionName, entry, i,
                            normalizedType, expected.toString(),
                            "ERROR", "ArgumentsValidator: " + functionName + "는 " + expected + " 인자 요구, " + argCategory + " 비호환");
                    }
                }
            }
        }

        private boolean isCompatible(TypeCategory actual, TypeCategory expected) {
            if (actual == expected) return true;
            if (expected == TypeCategory.NUMERIC && actual == TypeCategory.NUMERIC) return true;
            if (expected == TypeCategory.DATETIME && actual == TypeCategory.DATETIME) return true;
            return false;
        }

        private String normalizeTypeMirror(TypeMirror type) {
            if (type == null) return TypeNormalizer.UNRESOLVED;
            String typeStr = type.toString();
            if (!typeStr.startsWith("com.querydsl")) {
                return typeStr;
            }
            return TypeNormalizer.normalizeFromSignature(typeStr);
        }

        private void reportFinding(MethodInvocationTree node, TreePath path, Trees trees,
                                   String functionName, FunctionEntry entry,
                                   int argIdx, String actualType, String expectedType,
                                   String severity, String reason) {
            CompilationUnitTree cu = path.getCompilationUnit();
            SourcePositions positions = trees.getSourcePositions();
            long startPos = positions.getStartPosition(cu, node);
            long line = cu.getLineMap().getLineNumber(startPos);
            long col = cu.getLineMap().getColumnNumber(startPos);
            String fileName = cu.getSourceFile().getName();
            if (fileName.contains("/")) fileName = fileName.substring(fileName.lastIndexOf('/') + 1);

            String entity = "Unknown";
            String columnName = "Unknown";
            if (argIdx >= 0 && argIdx < node.getArguments().size()) {
                ExpressionTree arg = node.getArguments().get(argIdx);
                String argStr = arg.toString();
                if (argStr.contains(".")) {
                    String[] parts = argStr.split("\\.");
                    if (parts.length >= 2) {
                        String qClassName = parts[parts.length - 2];
                        columnName = parts[parts.length - 1];
                        if (qClassName.startsWith("q") && qClassName.length() > 1) {
                            entity = Character.toUpperCase(qClassName.charAt(1)) + qClassName.substring(2);
                        } else if (qClassName.startsWith("Q") && qClassName.length() > 1) {
                            entity = qClassName.substring(1);
                        } else {
                            entity = qClassName;
                        }
                    }
                }
            }

            String message = String.format(
                "[%s] %s:[%d,%d] JPQL 함수 타입 불일치%n" +
                "  함수       : %s (%s 카테고리)%n" +
                "  대상       : %s.%s (%s)%n" +
                "  인자 인덱스 : %d%n" +
                "  기대 타입   : %s%n" +
                "  판정 근거   : %s",
                severity, fileName, line, col,
                functionName, entry.category(),
                entity, columnName, actualType,
                argIdx,
                expectedType,
                reason
            );

            Diagnostic.Kind kind = "ERROR".equals(severity) ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;

            Element element = trees.getElement(path);
            if (element != null) {
                processingEnv.getMessager().printMessage(kind, message, element);
            } else {
                processingEnv.getMessager().printMessage(kind, message);
            }

            findings.add(new Finding(fileName, line, col, functionName,
                entity, columnName, actualType, expectedType, severity, reason));
        }
    }
}
