package edu.gmu.swe.knarr.runtime;

import static edu.neu.ccs.prl.galette.internal.runtime.symbolic.SymbolicOpcodes.*;

/**
 * Cheap runtime overflow check for symbolic arithmetic (CoCoPath/UncertainTree).
 *
 * <p>Java int/long arithmetic wraps silently; a solver encoding with mathematical integers
 * (planned for variables bounded by an uncertainty annotation) does not. The two agree as
 * long as no operation on a tagged value overflows. The listener calls this with the
 * concrete operands it sees anyway; an overflow is counted, and the first one described,
 * so that a client can mark the explored region as approximate.
 *
 * <p>Covered: IADD/ISUB/IMUL/IDIV/INEG, LADD/LSUB/LMUL/LDIV/LNEG, and D2I/D2L out of range
 * (Java saturates). Not covered (no concrete value in the listener interface): IINC and
 * the one-slot conversion F2I.
 */
public final class OverflowMonitor {
    private static int count;
    private static String first;

    private OverflowMonitor() {}

    public static int count() {
        return count;
    }

    /** Description of the first overflow since the last reset, or null. */
    public static String first() {
        return first;
    }

    public static void reset() {
        count = 0;
        first = null;
    }

    private static void record(String what) {
        if (count++ == 0) {
            first = what;
        }
    }

    static void checkInt(int opcode, int v1, int v2) {
        long exact;
        switch (opcode) {
            case IADD:
                exact = (long) v1 + v2;
                break;
            case ISUB:
                exact = (long) v1 - v2;
                break;
            case IMUL:
                exact = (long) v1 * v2;
                break;
            case IDIV:
                if (v1 == Integer.MIN_VALUE && v2 == -1) {
                    record("IDIV " + v1 + " / -1");
                }
                return;
            default:
                return;
        }
        if (exact != (int) exact) {
            record(name(opcode) + " " + v1 + ", " + v2 + " (exact " + exact + ")");
        }
    }

    static void checkIntUnary(int opcode, int v) {
        if (opcode == INEG && v == Integer.MIN_VALUE) {
            record("INEG " + v);
        }
    }

    static void checkLong(int opcode, long v1, long v2) {
        try {
            switch (opcode) {
                case LADD:
                    Math.addExact(v1, v2);
                    break;
                case LSUB:
                    Math.subtractExact(v1, v2);
                    break;
                case LMUL:
                    Math.multiplyExact(v1, v2);
                    break;
                case LDIV:
                    if (v1 == Long.MIN_VALUE && v2 == -1) {
                        record("LDIV " + v1 + " / -1");
                    }
                    break;
                default:
                    break;
            }
        } catch (ArithmeticException e) {
            record(name(opcode) + " " + v1 + ", " + v2);
        }
    }

    static void checkLongUnary(int opcode, long v) {
        if (opcode == LNEG && v == Long.MIN_VALUE) {
            record("LNEG " + v);
        }
    }

    static void checkDoubleConvert(int opcode, double v) {
        if (opcode == D2I && (v >= 2147483648.0 || v < -2147483648.0)) {
            record("D2I " + v + " out of int range");
        } else if (opcode == D2L && (v >= 9.223372036854775807E18 || v < -9.223372036854775808E18)) {
            record("D2L " + v + " out of long range");
        }
    }

    private static String name(int opcode) {
        switch (opcode) {
            case IADD: return "IADD";
            case ISUB: return "ISUB";
            case IMUL: return "IMUL";
            case LADD: return "LADD";
            case LSUB: return "LSUB";
            case LMUL: return "LMUL";
            default: return "op" + opcode;
        }
    }
}
