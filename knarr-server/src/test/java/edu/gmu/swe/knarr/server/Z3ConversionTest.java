package edu.gmu.swe.knarr.server;

import static org.junit.Assert.*;

import org.junit.Test;
import za.ac.sun.cs.green.Green;
import za.ac.sun.cs.green.Instance;
import za.ac.sun.cs.green.expr.BVConstant;
import za.ac.sun.cs.green.expr.BVVariable;
import za.ac.sun.cs.green.expr.BinaryOperation;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.Operation.Operator;
import za.ac.sun.cs.green.expr.RealConstant;
import za.ac.sun.cs.green.expr.UnaryOperation;
import za.ac.sun.cs.green.service.z3.ModelZ3JavaService;
import za.ac.sun.cs.green.service.z3.ModelZ3JavaService.Solution;
import za.ac.sun.cs.green.service.z3.Z3JavaTranslator.Z3GreenBridge;

/**
 * Conversion and rounding semantics added for CoCoPath/UncertainTree (2026-11-07):
 * BV2R (Java int -> float/double), R2BV (float/double -> int/long, truncation toward
 * zero) and ROUND (Math.round = floor(x + 0.5)). Mirrors the UncertainTree brake CPR
 * {@code radius = diameterInMM / 2.0f} and the reverse {@code Math.round}.
 */
public class Z3ConversionTest {
    private final Green green = new Green();
    private final ModelZ3JavaService solver = new ModelZ3JavaService(green, null);
    private final ConstraintOptionGenerator generator = new ConstraintOptionGenerator();

    private static final BVVariable D = new BVVariable("diameterInMM", 32);

    private static Expression and(Expression a, Expression b) {
        return new BinaryOperation(Operator.AND, a, b);
    }

    private static Expression radius() {
        return new BinaryOperation(Operator.DIV, new UnaryOperation(Operator.BV2R, D), new RealConstant(2.0));
    }

    private static Expression bv(int v) {
        return new BVConstant(v, 32);
    }

    @Test
    public void intToRealDivisionIsExact() {
        Expression le330 = new BinaryOperation(Operator.LE, D, bv(330));
        assertTrue(solve(and(and(new BinaryOperation(Operator.GT, radius(), new RealConstant(164.5)), le330),
                new BinaryOperation(Operator.GE, D, bv(325)))).sat);
        assertFalse("d <= 330 implies d/2 <= 165",
                solve(and(new BinaryOperation(Operator.GT, radius(), new RealConstant(165.0)), le330)).sat);
    }

    @Test
    public void roundIsFloorOfXPlusHalf() {
        Expression d325 = new BinaryOperation(Operator.EQ, D, bv(325));
        Expression rounded = new UnaryOperation(Operator.R2BV, 32, new UnaryOperation(Operator.ROUND, radius()));
        assertTrue("Math.round(162.5f) == 163",
                solve(and(d325, new BinaryOperation(Operator.EQ, rounded, bv(163)))).sat);
        assertFalse(solve(and(d325, new BinaryOperation(Operator.EQ, rounded, bv(162)))).sat);
    }

    @Test
    public void realToIntTruncatesTowardZero() {
        Expression t = new UnaryOperation(Operator.R2BV, 32, new RealConstant(-2.5));
        BVVariable y = new BVVariable("y", 32);
        assertTrue("(int) -2.5 == -2",
                solve(and(new BinaryOperation(Operator.EQ, y, t), new BinaryOperation(Operator.EQ, y, bv(-2)))).sat);
        assertFalse(solve(and(new BinaryOperation(Operator.EQ, y, t), new BinaryOperation(Operator.EQ, y, bv(-3)))).sat);
    }

    @Test
    public void mixedRealAndBitVectorComparison() {
        // Shape recorded from MultiModelOCL on UncertainTree CPS1: 27225.0 <= R2BV(r * r)
        Expression r2 = new UnaryOperation(Operator.R2BV, 32,
                new BinaryOperation(Operator.MUL, radius(), radius()));
        Expression c = new BinaryOperation(Operator.LE, new RealConstant(27225.0), r2);
        assertTrue(solve(and(c, new BinaryOperation(Operator.EQ, D, bv(330)))).sat);
        assertFalse(solve(and(c, new BinaryOperation(Operator.EQ, D, bv(329)))).sat);
    }

    private Solution solve(Expression expr) {
        Instance inst = new Instance(green, null, expr);
        Z3GreenBridge bridge = solver.getUnderlyingExpr(inst);
        Z3GreenBridge[] options = generator.generateOptions(bridge).toArray(new Z3GreenBridge[0]);
        return options.length == 0 ? solver.solve(bridge) : solver.solve(options[0]);
    }
}
