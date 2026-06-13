# Jackson Creator Detector Maven Plugin — Requirements
See uploaded requirements document for full details.
Implements: javac AST-based detection of dangerous DTO patterns for Jackson 2.12/2.18 creator rule changes.
Detection scopes: CACHE_REACHABLE, BASE_MODEL_HIERARCHY
Risk levels: R1-R4 (dangerous), S1-S5 (safe/excluded)
Java 8+ runtime support (JRE fail-fast required)
Read-only: no source file modification
