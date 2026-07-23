package com.editora.command;

/**
 * The Emacs prefix (universal) argument state machine: the number a {@code C-u} sequence accumulates
 * before the command it applies to. Pure and toolkit-free; {@link KeyDispatcher} drives it from key
 * events and then repeats the next command that many times (or hands the value to a command that reads it).
 *
 * <p>Accumulation follows GNU Emacs:
 *
 * <ul>
 *   <li>{@code C-u} → 4; {@code C-u C-u} → 16; each further bare {@code C-u} multiplies by four.
 *   <li>{@code C-u 3} → 3, {@code C-u 3 7} → 37 (digits typed after {@code C-u} form the number).
 *   <li>{@code C-u -} → −1, {@code C-u - 5} → −5 (a minus <em>before</em> any digit negates).
 *   <li>a minus <em>after</em> digits is not part of the argument — the caller treats it as the command
 *       (so {@code C-u 10 -} inserts ten dashes), which is why {@link #hasDigits()} is exposed.
 * </ul>
 *
 * <p>Editora binds only {@code C-u} to start this (the {@code M-1}…{@code M-9} digit-argument chords are
 * taken by the tool windows), so a numeric argument is always entered as {@code C-u} then digits.
 */
public final class PrefixArg {

    private boolean active;
    private int universalCount; // number of bare C-u presses; 0 once digits are entered
    private boolean hasDigits;
    private int digits;
    private boolean negative;

    public boolean isActive() {
        return active;
    }

    public boolean hasDigits() {
        return hasDigits;
    }

    /** A {@code C-u}: start the argument, or (with no digits yet) multiply the running value by four. */
    public void universal() {
        if (!active) {
            active = true;
            universalCount = 1;
        } else if (!hasDigits) {
            universalCount++;
        } else {
            digits *= 4; // C-u after a numeric argument multiplies it, keeping any sign
        }
    }

    /** A digit key while the argument is active: append it (and drop the {@code C-u} multiplier). */
    public void digit(int d) {
        active = true;
        hasDigits = true;
        universalCount = 0;
        digits = digits * 10 + d;
    }

    /** A leading minus: negate. Only meaningful before any digit (the caller enforces that). */
    public void negate() {
        active = true;
        if (!hasDigits) {
            universalCount = 0; // a leading minus discards any pending C-u multiplier: C-u - is -1, not -4
        }
        negative = !negative;
    }

    /** The accumulated numeric value; 1 when active with nothing entered, −1 for a lone minus. */
    public int value() {
        if (hasDigits) {
            return negative ? -digits : digits;
        }
        if (universalCount > 0) {
            int v = 1;
            for (int i = 0; i < universalCount; i++) {
                v *= 4;
            }
            return negative ? -v : v;
        }
        return negative ? -1 : 1;
    }

    /** The echo-area rendering while the argument is being entered ({@code ""} when inactive). */
    public String describe() {
        if (!active) {
            return "";
        }
        if (!hasDigits && negative && universalCount == 0) {
            return "C-u -";
        }
        return "C-u " + value();
    }

    public void reset() {
        active = false;
        universalCount = 0;
        hasDigits = false;
        digits = 0;
        negative = false;
    }
}
