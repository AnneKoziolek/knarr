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

    // ---- bounded-integer mode (variables with an annotation domain) ----

    private static final za.ac.sun.cs.green.expr.IntVariable DI =
            new za.ac.sun.cs.green.expr.IntVariable("d_int", 325, 337);

    private static Expression ic(int v) {
        return new za.ac.sun.cs.green.expr.IntConstant(v);
    }

    @Test
    public void boundedIntMixesWithBitVectorConstants() {
        // Java constants arrive as 32-bit BVConstants; d_int is an Int. d != 0 and d <= 330.
        Expression c = and(new BinaryOperation(Operator.NE, DI, bv(0)), new BinaryOperation(Operator.LE, DI, bv(330)));
        assertTrue(satDirect(and(c, new BinaryOperation(Operator.EQ, DI, ic(330)))));
        assertFalse(satDirect(and(c, new BinaryOperation(Operator.EQ, DI, ic(331)))));
    }

    @Test
    public void boundedIntCylinderConstraintAsRecorded() {
        // CPS1 shape with d as bounded integer: 27225.0 <= R2BV((BV2R(sext(d))/2.0)^2)
        Expression d = new UnaryOperation(Operator.EXTRACT, 31, 0, new UnaryOperation(Operator.SIGN_EXT, 32, DI));
        Expression r = new BinaryOperation(Operator.DIV, new UnaryOperation(Operator.BV2R, d), new RealConstant(2.0));
        Expression c = new BinaryOperation(Operator.LE, new RealConstant(27225.0),
                new UnaryOperation(Operator.R2BV, 32, new BinaryOperation(Operator.MUL, r, r)));
        assertTrue(satDirect(and(c, new BinaryOperation(Operator.EQ, DI, ic(330)))));
        assertFalse(satDirect(and(c, new BinaryOperation(Operator.EQ, DI, ic(329)))));
    }

    @Test
    public void boundedIntDivisionAndModuloFollowJava() {
        za.ac.sun.cs.green.expr.IntVariable a = new za.ac.sun.cs.green.expr.IntVariable("a_int", -10, 10);
        // Java: -7 / 2 == -3 (SMT div would give -4), -7 % 2 == -1
        Expression a7 = new BinaryOperation(Operator.EQ, a, ic(-7));
        assertTrue(satDirect(and(a7, new BinaryOperation(Operator.EQ, new BinaryOperation(Operator.DIV, a, ic(2)), ic(-3)))));
        assertFalse(satDirect(and(a7, new BinaryOperation(Operator.EQ, new BinaryOperation(Operator.DIV, a, ic(2)), ic(-4)))));
        assertTrue(satDirect(and(a7, new BinaryOperation(Operator.EQ, new BinaryOperation(Operator.MOD, a, ic(2)), ic(-1)))));
    }

    /**
     * Direct translation and check, as uncertaintree-mode2's Mode2SolverServer does (no
     * ConstraintOptionGenerator, which rebuilds expressions and does not know every Z3
     * operator). Used for the bounded-integer mode.
     */
    private static boolean satDirect(Expression expr) {
        try (com.microsoft.z3.Context ctx = new com.microsoft.z3.Context()) {
            za.ac.sun.cs.green.service.z3.Z3JavaTranslator t = new za.ac.sun.cs.green.service.z3.Z3JavaTranslator(ctx);
            expr.accept(t);
            com.microsoft.z3.Solver s = ctx.mkSolver();
            s.add((com.microsoft.z3.BoolExpr) t.getTranslation());
            return s.check() == com.microsoft.z3.Status.SATISFIABLE;
        } catch (za.ac.sun.cs.green.expr.VisitorException e) {
            throw new RuntimeException(e);
        }
    }

    private Solution solve(Expression expr) {
        Instance inst = new Instance(green, null, expr);
        Z3GreenBridge bridge = solver.getUnderlyingExpr(inst);
        Z3GreenBridge[] options = generator.generateOptions(bridge).toArray(new Z3GreenBridge[0]);
        return options.length == 0 ? solver.solve(bridge) : solver.solve(options[0]);
    }
}
