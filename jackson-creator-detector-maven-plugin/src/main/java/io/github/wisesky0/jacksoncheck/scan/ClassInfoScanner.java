package io.github.wisesky0.jacksoncheck.scan;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import io.github.wisesky0.jacksoncheck.model.ClassInfo;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.util.*;

public class ClassInfoScanner extends TreePathScanner<Void, List<ClassInfo>> {

    private static final Set<String> CACHE_ANNOTATIONS = Set.of(
        "Cacheable", "CachePut", "CacheEvict", "Caching",
        "org.springframework.cache.annotation.Cacheable",
        "org.springframework.cache.annotation.CachePut",
        "org.springframework.cache.annotation.CacheEvict",
        "org.springframework.cache.annotation.Caching");

    private static final Set<String> LOMBOK_CTOR_ANNOTATIONS = Set.of(
        "NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor",
        "Builder", "Data", "Value", "Jacksonized",
        "lombok.NoArgsConstructor", "lombok.AllArgsConstructor",
        "lombok.RequiredArgsConstructor", "lombok.Builder",
        "lombok.Data", "lombok.Value",
        "lombok.extern.jackson.Jacksonized");

    private final Trees trees;
    private final Deque<ClassInfo> classStack = new ArrayDeque<>();

    public ClassInfoScanner(Trees trees) {
        this.trees = trees;
    }

    @Override
    public Void visitClass(ClassTree node, List<ClassInfo> results) {
        ClassInfo info = new ClassInfo();

        // Source position
        CompilationUnitTree cu = getCurrentPath().getCompilationUnit();
        SourcePositions pos = trees.getSourcePositions();
        long start = pos.getStartPosition(cu, node);
        info.line = cu.getLineMap().getLineNumber(start);
        info.sourceFile = cu.getSourceFile().getName();
        if (info.sourceFile.contains("/")) {
            info.sourceFile = info.sourceFile.substring(info.sourceFile.lastIndexOf('/') + 1);
        }

        // FQCN via element
        Element el = trees.getElement(getCurrentPath());
        if (el instanceof TypeElement te) {
            info.fqcn = te.getQualifiedName().toString();
            info.isEnum = te.getKind() == ElementKind.ENUM;
            info.isInterface = te.getKind() == ElementKind.INTERFACE;
            info.isRecord = te.getKind() == ElementKind.RECORD;
            info.isAbstract = te.getModifiers().contains(Modifier.ABSTRACT) && !info.isInterface;

            // Superclass
            TypeMirror superMirror = te.getSuperclass();
            if (superMirror instanceof DeclaredType dt) {
                Element superEl = dt.asElement();
                if (superEl instanceof TypeElement ste) {
                    String superFqcn = ste.getQualifiedName().toString();
                    if (!"java.lang.Object".equals(superFqcn)) {
                        info.superFqcn = superFqcn;
                    }
                }
            }
        } else {
            // Fallback: use simple name from tree
            info.fqcn = node.getSimpleName().toString();
            // Try to get kind from Tree
            info.isEnum = node.getKind() == Tree.Kind.ENUM;
            info.isInterface = node.getKind() == Tree.Kind.INTERFACE;
        }

        // Class-level Lombok annotations
        for (AnnotationTree ann : node.getModifiers().getAnnotations()) {
            String annName = simpleName(ann.getAnnotationType().toString());
            if (LOMBOK_CTOR_ANNOTATIONS.contains(annName)) {
                info.constructors.lombokAnnotations.add(annName);
            }
        }

        classStack.push(info);
        super.visitClass(node, results);
        classStack.pop();

        if (info.fqcn != null && !info.fqcn.isEmpty()) {
            results.add(info);
        }
        return null;
    }

    @Override
    public Void visitMethod(MethodTree node, List<ClassInfo> results) {
        if (classStack.isEmpty()) return super.visitMethod(node, results);
        ClassInfo current = classStack.peek();

        boolean isConstructor = node.getReturnType() == null;

        if (isConstructor) {
            int paramCount = node.getParameters().size();
            current.constructors.explicitParamCounts.add(paramCount);

            // Check for @JsonCreator on this constructor
            for (AnnotationTree ann : node.getModifiers().getAnnotations()) {
                String name = simpleName(ann.getAnnotationType().toString());
                if ("JsonCreator".equals(name)) {
                    current.constructors.hasJsonCreatorOnCtor = true;
                }
            }
        } else {
            // Check for cache annotations
            boolean hasCacheAnnotation = false;
            for (AnnotationTree ann : node.getModifiers().getAnnotations()) {
                String name = ann.getAnnotationType().toString();
                if (CACHE_ANNOTATIONS.contains(simpleName(name)) || CACHE_ANNOTATIONS.contains(name)) {
                    hasCacheAnnotation = true;
                    break;
                }
            }

            if (hasCacheAnnotation) {
                // Collect return type FQCN
                Element methodEl = trees.getElement(getCurrentPath());
                if (methodEl instanceof ExecutableElement exe) {
                    String retFqcn = typeFqcn(exe.getReturnType());
                    if (retFqcn != null) current.cacheReturnTypes.add(retFqcn);
                    for (var param : exe.getParameters()) {
                        String paramFqcn = typeFqcn(param.asType());
                        if (paramFqcn != null) current.cacheParamTypes.add(paramFqcn);
                    }
                }
            }
        }

        return super.visitMethod(node, results);
    }

    private String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private String typeFqcn(TypeMirror tm) {
        if (tm == null || tm.getKind() == TypeKind.VOID || tm.getKind() == TypeKind.NONE) return null;
        if (tm instanceof DeclaredType dt) {
            Element el = dt.asElement();
            if (el instanceof TypeElement te) return te.getQualifiedName().toString();
        }
        return null;
    }
}
