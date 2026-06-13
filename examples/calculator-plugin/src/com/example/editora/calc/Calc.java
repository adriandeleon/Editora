package com.example.editora.calc;

/**
 * A pure recursive-descent arithmetic evaluator: {@code + - * / %}, {@code ^} (right-assoc power), unary
 * {@code +/-}, and parentheses. Throws {@link IllegalArgumentException} on malformed input.
 */
final class Calc {

    private final String s;
    private int pos;

    private Calc(String s) {
        this.s = s;
    }

    static double eval(String expr) {
        Calc c = new Calc(expr == null ? "" : expr);
        double v = c.expr();
        c.skipWs();
        if (c.pos < c.s.length()) {
            throw new IllegalArgumentException("unexpected '" + c.s.charAt(c.pos) + "'");
        }
        return v;
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private boolean eat(char c) {
        skipWs();
        if (pos < s.length() && s.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private double expr() { // term (('+'|'-') term)*
        double x = term();
        while (true) {
            if (eat('+')) {
                x += term();
            } else if (eat('-')) {
                x -= term();
            } else {
                return x;
            }
        }
    }

    private double term() { // factor (('*'|'/'|'%') factor)*
        double x = factor();
        while (true) {
            if (eat('*')) {
                x *= factor();
            } else if (eat('/')) {
                x /= factor();
            } else if (eat('%')) {
                x %= factor();
            } else {
                return x;
            }
        }
    }

    private double factor() { // ('+'|'-') factor | power
        if (eat('+')) {
            return factor();
        }
        if (eat('-')) {
            return -factor();
        }
        return power();
    }

    private double power() { // primary ('^' factor)?
        double base = primary();
        if (eat('^')) {
            return Math.pow(base, factor());
        }
        return base;
    }

    private double primary() {
        skipWs();
        if (eat('(')) {
            double v = expr();
            if (!eat(')')) {
                throw new IllegalArgumentException("missing ')'");
            }
            return v;
        }
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'
                || s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            pos++;
        }
        if (pos == start) {
            throw new IllegalArgumentException(pos < s.length()
                    ? "unexpected '" + s.charAt(pos) + "'" : "unexpected end of expression");
        }
        try {
            return Double.parseDouble(s.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number '" + s.substring(start, pos) + "'");
        }
    }

    /** Formats a result, dropping a trailing {@code .0} for integral values. */
    static String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
