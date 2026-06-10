package io.github.wisesky0.jpqlcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QueryDSL 템플릿 문자열 파서 (PRD 5장 판정 알고리즘 1단계).
 *
 * 템플릿 문자열에서 함수 호출 형태(식별자 + 괄호)를 추출한다.
 * - function('NAME', ...) 표준 구문은 viaFunctionSyntax=true 로 표시한다 (PRD E-02)
 * - 인자가 cast(..)로 감싸진 경우는 호출 측에서 E-01 제외 판정에 사용한다
 */
public final class TemplateParser {

    /** 템플릿 내 함수 호출 1건. args는 최상위 콤마 기준으로 분리한 인자 원문. */
    public record FunctionCall(String name, List<String> args, boolean viaFunctionSyntax) {}

    public record ParsedTemplate(String raw, List<FunctionCall> calls) {

        /** 카탈로그에 없는(미등록) 함수 호출 목록. function('..') 구문은 제외한다 (E-02). */
        public List<FunctionCall> unregisteredCalls() {
            List<FunctionCall> result = new ArrayList<>();
            for (FunctionCall c : calls) {
                if (c.viaFunctionSyntax()) continue;
                if ("function".equalsIgnoreCase(c.name())) continue;
                if (!FunctionCatalog.isRegistered(c.name())) result.add(c);
            }
            return result;
        }

        /** 카탈로그에 등록된 함수 호출 목록. function('..') 구문은 제외한다. */
        public List<FunctionCall> registeredCalls() {
            List<FunctionCall> result = new ArrayList<>();
            for (FunctionCall c : calls) {
                if (c.viaFunctionSyntax()) continue;
                if ("function".equalsIgnoreCase(c.name())) continue;
                if (FunctionCatalog.isRegistered(c.name())) result.add(c);
            }
            return result;
        }
    }

    private static final Pattern BARE_PLACEHOLDER = Pattern.compile("^\\s*\\{(\\d+)}\\s*$");
    private static final Pattern CAST_CALL = Pattern.compile("(?i)\\bcast\\s*\\(");

    private TemplateParser() {}

    public static ParsedTemplate parse(String raw) {
        List<FunctionCall> calls = new ArrayList<>();
        if (raw != null) scan(raw, calls);
        return new ParsedTemplate(raw, calls);
    }

    /** 인자 원문이 단일 플레이스홀더({n})이면 n을, 아니면 -1을 반환한다. */
    public static int barePlaceholderIndex(String arg) {
        if (arg == null) return -1;
        Matcher m = BARE_PLACEHOLDER.matcher(arg);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 인자 원문에 cast(..) 적용 여부 (PRD E-01). */
    public static boolean containsCast(String arg) {
        return arg != null && CAST_CALL.matcher(arg).find();
    }

    private static void scan(String s, List<FunctionCall> out) {
        int i = 0;
        boolean inQuote = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
                continue;
            }
            if (inQuote) {
                i++;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < s.length() && Character.isJavaIdentifierPart(s.charAt(i))) i++;
                String ident = s.substring(start, i);
                int j = i;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && s.charAt(j) == '(') {
                    int close = findMatchingParen(s, j);
                    if (close > j) {
                        String inner = s.substring(j + 1, close);
                        List<String> args = splitTopLevel(inner);
                        addCall(ident, args, out);
                        // 중첩 함수도 추출하기 위해 괄호 내부부터 계속 스캔한다
                        i = j + 1;
                        continue;
                    }
                }
            } else {
                i++;
            }
        }
    }

    private static void addCall(String ident, List<String> args, List<FunctionCall> out) {
        if ("function".equalsIgnoreCase(ident) && !args.isEmpty()) {
            String first = args.get(0).trim();
            if (first.length() >= 2 && first.startsWith("'") && first.endsWith("'")) {
                String fnName = first.substring(1, first.length() - 1).trim().toLowerCase(Locale.ROOT);
                out.add(new FunctionCall(fnName, args.subList(1, args.size()), true));
                return;
            }
        }
        out.add(new FunctionCall(ident.toLowerCase(Locale.ROOT), args, false));
    }

    private static int findMatchingParen(String s, int openIdx) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (inQuote) continue;
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String inner) {
        List<String> args = new ArrayList<>();
        if (inner.isBlank()) return args;
        int depth = 0;
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (!inQuote) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) {
                    args.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        args.add(cur.toString());
        return args;
    }
}
