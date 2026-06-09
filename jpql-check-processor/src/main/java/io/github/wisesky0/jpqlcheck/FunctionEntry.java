package io.github.wisesky0.jpqlcheck;

import java.util.List;

public record FunctionEntry(
    String name,
    TypeCategory category,
    int minArgs,
    int maxArgs, // -1 = unbounded
    List<TypeCategory> argCategories, // null = all must match category
    String source,      // JPA_STANDARD or HIBERNATE_EXTENSION
    String minJpaVersion
) {}
