package io.github.wisesky0.jacksoncheck.model;

public enum RiskLevel {
    R1("위험(상) — 단일 인자 + all-args 생성자, 기본 생성자 없음"),
    R2("위험(상) — 단일 인자 생성자만, 기본 생성자 없음"),
    R3("위험(중) — 다중 인자 생성자(2개 이상), 기본 생성자 없음"),
    R4("위험(하) — all-args 생성자 1개만, 기본 생성자 없음"),
    SAFE("안전"),
    EXCLUDED("제외");

    private final String description;
    RiskLevel(String d) { this.description = d; }
    public String getDescription() { return description; }
    public boolean isRisky() {
        return this == R1 || this == R2 || this == R3 || this == R4;
    }
    // ordinal: R1=0 < R2=1 < R3=2 < R4=3 < SAFE=4 < EXCLUDED=5
    public boolean atLeastAsRiskyAs(RiskLevel threshold) {
        return this.isRisky() && this.ordinal() <= threshold.ordinal();
    }
}
