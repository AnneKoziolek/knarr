package edu.gmu.swe.knarr.runtime;

import za.ac.sun.cs.green.expr.BinaryOperation;
import za.ac.sun.cs.green.expr.Expression;

/**
 * Result tag of an {@code LCMP}/{@code FCMPx}/{@code DCMPx}: the difference {@code l - r}
 * of the two compared operands, kept as a marker so that the {@code IF<cond>} that
 * consumes the comparison result can record the exact relational predicate
 * ({@code IFGE} on it means {@code l >= r}, etc.) instead of the three-way outcome.
 *
 * <p>javac always emits the compare instruction directly before the branch and never
 * stores the -1/0/1 result, so this is exact for javac-generated code. If the marker
 * is consumed by anything other than a branch, it still denotes {@code l - r}, whose
 * sign matches the compare result.
 */
public final class CmpDifference extends BinaryOperation {
    private final Expression left;
    private final Expression right;

    public CmpDifference(Expression left, Expression right) {
        super(Operator.SUB, left, right);
        this.left = left;
        this.right = right;
    }

    public Expression left() {
        return left;
    }

    public Expression right() {
        return right;
    }
}
