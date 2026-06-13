package io.github.wisesky0.jpqlcheck.join;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import io.github.wisesky0.jpqlcheck.Finding;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * PRD2 D-10: QueryDSL .from(A, B).innerJoin(C).on(predicate) 체인에서
 * ON 절 predicate가 JOIN이 속한 루트 트리 밖의 FROM 루트를 참조하는 경우를 감지한다.
 *
 * Hibernate 6 QualifiedJoinPredicatePathConsumer.validateAsRoot() 기반:
 *   - 직렬화된 JPQL에서 명시적 JOIN들은 마지막 FROM 루트 트리에 소속
 *   - ON 절 참조가 소속 트리 밖(다른 FROM 루트)이면 SemanticException
 */
public class JoinChainAnalyzer extends TreePathScanner<Void, Void> {

    private static final Set<String> JOIN_METHODS = Set.of(
        "innerJoin", "leftJoin", "rightJoin", "join", "crossJoin", "fullJoin");

    private final Trees trees;
    private final ProcessingEnvironment processingEnv;
    private final List<Finding> findings;
    private final boolean failOnError;

    public JoinChainAnalyzer(Trees trees, ProcessingEnvironment processingEnv,
                              List<Finding> findings, boolean failOnError) {
        this.trees = trees;
        this.processingEnv = processingEnv;
        this.findings = findings;
        this.failOnError = failOnError;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        if (isOnCall(node)) {
            checkOnClause(node);
        }
        return super.visitMethodInvocation(node, unused);
    }

    private boolean isOnCall(MethodInvocationTree node) {
        ExpressionTree sel = node.getMethodSelect();
        return sel instanceof MemberSelectTree ms
            && "on".equals(ms.getIdentifier().toString())
            && !node.getArguments().isEmpty();
    }

    private void checkOnClause(MethodInvocationTree onNode) {
        MemberSelectTree ms = (MemberSelectTree) onNode.getMethodSelect();

        // 체인을 역추적하여 FROM 루트 수집
        List<String> fromRoots = new ArrayList<>();
        walkChain(ms.getExpression(), fromRoots);

        // FROM 루트 1개 이하이면 교차 참조 위반 불가 (E-10)
        if (fromRoots.size() <= 1) return;

        String attachedRoot = fromRoots.get(fromRoots.size() - 1);  // 마지막 FROM 루트
        Set<String> fromRootSet = new HashSet<>(fromRoots);

        // ON predicate에서 경로 루트 식별자 수집
        ExpressionTree predicate = strip(onNode.getArguments().get(0));
        Set<String> predicateRoots = new LinkedHashSet<>();
        collectPathRoots(predicate, predicateRoots);

        for (String root : predicateRoots) {
            if (fromRootSet.contains(root) && !root.equals(attachedRoot)) {
                report(onNode, root, attachedRoot, fromRoots);
                return;  // ON 절 1건당 최초 위반만 보고
            }
        }
    }

    /**
     * 체인을 안쪽(시작)에서 바깥쪽(현재)으로 재귀 탐색하여
     * from() 호출의 인자를 fromRoots에 선언 순서대로 수집한다.
     * 재귀 후 처리 방식으로 from() 인자가 체인 내 선언 순서를 유지한다.
     */
    private void walkChain(ExpressionTree expr, List<String> fromRoots) {
        if (!(expr instanceof MethodInvocationTree mit)) return;
        ExpressionTree sel = mit.getMethodSelect();
        if (!(sel instanceof MemberSelectTree ms)) return;

        // 안쪽 먼저 재귀 → from() 인자가 선언 순서대로 수집됨
        walkChain(ms.getExpression(), fromRoots);

        String name = ms.getIdentifier().toString();
        if ("from".equals(name)) {
            for (ExpressionTree arg : mit.getArguments()) {
                String n = rootIdentifierName(arg);
                if (n != null) fromRoots.add(n);
            }
        }
        // join 계열, on, where 등은 fromRoots 수집 불필요
    }

    /** 표현식의 최상위 식별자 이름. TypeCast 제거 후 IdentifierTree이면 이름, 아니면 null. */
    private String rootIdentifierName(ExpressionTree expr) {
        expr = strip(expr);
        if (expr instanceof TypeCastTree tct) expr = strip(tct.getExpression());
        return expr instanceof IdentifierTree id ? id.getName().toString() : null;
    }

    /**
     * predicate 트리 내 경로 루트 식별자를 수집한다.
     *
     * 처리 규칙:
     * - MemberSelectTree (a.b.c): 가장 안쪽 IdentifierTree가 루트 (qEntity.field → "qEntity")
     * - MethodInvocationTree (a.m(b)): 수신자 표현식과 인자를 모두 재귀 처리
     * - IdentifierTree (a): 직접 사용된 경로 변수 (예: c.eq(a) 의 수신자 c, 인자 a)
     *   — fromRootSet 필터로 실제 FROM 루트만 위반으로 판정하므로 오탐 위험 낮음
     */
    private void collectPathRoots(ExpressionTree expr, Set<String> roots) {
        if (expr == null) return;
        expr = strip(expr);

        if (expr instanceof MemberSelectTree ms) {
            ExpressionTree inner = ms.getExpression();
            while (inner instanceof MemberSelectTree innerMs) {
                inner = innerMs.getExpression();
            }
            inner = strip(inner);
            if (inner instanceof IdentifierTree id) {
                roots.add(id.getName().toString());
            } else {
                collectPathRoots(inner, roots);
            }
        } else if (expr instanceof MethodInvocationTree mit) {
            if (mit.getMethodSelect() instanceof MemberSelectTree ms) {
                collectPathRoots(ms.getExpression(), roots);
            }
            for (ExpressionTree arg : mit.getArguments()) {
                collectPathRoots(arg, roots);
            }
        } else if (expr instanceof IdentifierTree id) {
            // 단독 식별자로 사용되는 QueryDSL 경로 변수 (예: c.eq(a) 의 수신자·인자)
            // 실제 코드의 qEntity.field 패턴은 MemberSelectTree 경로로 처리되지만,
            // 심플 변수 참조도 수집하여 fromRootSet 필터에서 최종 판정한다
            roots.add(id.getName().toString());
        }
    }

    private ExpressionTree strip(ExpressionTree expr) {
        while (expr instanceof ParenthesizedTree p) expr = p.getExpression();
        return expr;
    }

    private void report(MethodInvocationTree node, String crossRoot,
                        String attachedRoot, List<String> fromRoots) {
        CompilationUnitTree cu = getCurrentPath().getCompilationUnit();
        SourcePositions pos = trees.getSourcePositions();
        long start = pos.getStartPosition(cu, node);
        long line = cu.getLineMap().getLineNumber(start);
        long col = cu.getLineMap().getColumnNumber(start);
        String fileName = cu.getSourceFile().getName();
        if (fileName.contains("/")) fileName = fileName.substring(fileName.lastIndexOf('/') + 1);

        String fromList = String.join(", ", fromRoots);
        String reason = "JOIN ON 절이 해당 JOIN이 속한 루트 트리 밖의 FROM 루트("
            + crossRoot + ")를 참조합니다"
            + " — Hibernate 6 SemanticException 발생";
        String recommendation = "의도한 루트(" + attachedRoot
            + ")로 교정하거나, 교차 조건을 WHERE 절로 이동하거나, "
            + "FROM 다중 루트를 JOIN 체인으로 재구성하십시오";

        StringBuilder msg = new StringBuilder();
        msg.append("[D-10] ").append(fileName)
           .append(":[").append(line).append(",").append(col).append("] ").append(reason);
        msg.append("\n  JOIN 소속 루트 : ").append(attachedRoot).append(" (마지막 FROM 루트)");
        msg.append("\n  ON 절 위반 참조: ").append(crossRoot);
        msg.append("\n  FROM 루트 목록 : ").append(fromList);
        msg.append("\n  권장 조치     : ").append(recommendation);

        Diagnostic.Kind kind = failOnError ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
        processingEnv.getMessager().printMessage(kind, msg.toString());

        findings.add(new Finding("D-10", "ERROR", fileName, line, col,
            null, null, crossRoot, "FROM_ROOT", attachedRoot,
            -1, reason, recommendation));
    }
}
