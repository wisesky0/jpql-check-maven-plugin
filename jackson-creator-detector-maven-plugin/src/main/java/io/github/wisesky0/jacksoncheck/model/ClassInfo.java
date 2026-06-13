package io.github.wisesky0.jacksoncheck.model;

import java.util.ArrayList;
import java.util.List;

public final class ClassInfo {
    public String fqcn;
    public boolean isAbstract;
    public boolean isEnum;
    public boolean isInterface;
    public boolean isRecord;
    public String superFqcn;           // null if java.lang.Object or unresolved
    public final ConstructorShape constructors = new ConstructorShape();
    public final List<String> cacheReturnTypes = new ArrayList<>();
    public final List<String> cacheParamTypes = new ArrayList<>();
    public String sourceFile;
    public long line;
    // filled in post-processing
    public boolean extendsBaseModel;
}
