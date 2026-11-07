package edu.gmu.swe.knarr;

import edu.gmu.swe.knarr.runtime.PathConstraintListener;
import edu.gmu.swe.knarr.runtime.PathUtils;
import edu.gmu.swe.knarr.runtime.Symbolicator;
import edu.neu.ccs.prl.galette.internal.runtime.symbolic.SymbolicListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.ac.sun.cs.green.expr.Expression;

/**
 * Probes for UncertainTree Mode 2 (CoCoPath on Bell's Knarr/Galette), 2026-11-07.
 *
 * <p>Each probe mirrors a construct of the UncertainTree brake case: the CPR
 * {@code radius = diameterInMM / 2.0f} (int to float) and the OCL caliper check
 * {@code dx*dx + dy*dy > r*r} (double arithmetic, symbolic on one side), plus a
 * comparison with symbolic expressions on both sides. Assertions only check that
 * a constraint was recorded; the printed constraint text is the actual result.
 */
public class CoCoPathModeTwoProbeITCase {

    @BeforeEach
    void installListener() {
        SymbolicListener.setListener(new PathConstraintListener());
    }

    @AfterEach
    void uninstallListener() {
        SymbolicListener.setListener(null);
        Symbolicator.reset();
    }

    private static String pc() {
        Expression e = PathUtils.getCurPC().constraints;
        return e == null ? "<none>" : e.toString();
    }

    @Test
    void doubleArithmeticBranch() {
        double d = Symbolicator.symbolic("d_double", 330.0);
        double r = d / 2.0;
        boolean intersects = r * r > 27000.0;
        if (intersects) {
            System.out.println("[probe] double branch taken");
        }
        System.out.println("[probe] doubleArithmeticBranch PC: " + pc());
        Assertions.assertNotNull(PathUtils.getCurPC().constraints, "double branch should record a constraint");
    }

    @Test
    void intToFloatThenBranch() {
        int diameterInMM = Symbolicator.symbolic("diameterInMM", 330);
        float radius = diameterInMM / 2.0f;
        System.out.println("[probe] radius expression: " + Symbolicator.getExpression(radius));
        Assertions.assertTrue(String.valueOf(Symbolicator.getExpression(radius)).contains("BV2R"),
                "int-to-float must be an explicit BV2R conversion");
        if (radius > 164.5f) {
            System.out.println("[probe] float branch taken");
        }
        System.out.println("[probe] intToFloatThenBranch PC: " + pc());
        Assertions.assertNotNull(PathUtils.getCurPC().constraints, "float branch after I2F should record a constraint");
    }

    @Test
    void symbolicExpressionsOnBothSides() {
        int a = Symbolicator.symbolic("a", 5);
        int b = Symbolicator.symbolic("b", 3);
        if (a + 5 > b * 2) {
            System.out.println("[probe] two-sided branch taken");
        }
        System.out.println("[probe] symbolicExpressionsOnBothSides PC: " + pc());
        Assertions.assertNotNull(PathUtils.getCurPC().constraints, "two-sided branch should record a constraint");
    }

    @Test
    void longAndDoubleComparisons() {
        long l = Symbolicator.symbolic("l", 10L);
        if (l * 2L >= 21L) {
            System.out.println("[probe] long branch taken");
        }
        double x = Symbolicator.symbolic("x_double", 1.5);
        double y = Symbolicator.symbolic("y_double", 2.5);
        if (x + 1.0 < y) {
            System.out.println("[probe] double two-sided branch taken");
        }
        String pc = pc();
        System.out.println("[probe] longAndDoubleComparisons PC: " + pc);
        // Not-taken branches must be recorded as the negated branch predicate,
        // not as the three-way compare outcome (x+1.0 == y at these values).
        // javac compiles "if (a < b)" as xCMP + IFGE (jump to else), so the
        // not-taken branch appears as ">=" (and "l*2 >= 21" as "< 21").
        Assertions.assertTrue(pc.contains("((l*2)<21)"), pc);
        Assertions.assertTrue(pc.contains("((x_double+1.0)>=y_double)"), pc);
        Assertions.assertFalse(pc.contains("=="), "three-way outcome must not be recorded: " + pc);
    }

    @Test
    void mathRoundRoundTripStaysSymbolic() {
        // UncertainTree brake: brakesystem2cad writes radius = d / 2.0f, cad2brakesystem
        // writes back diameterInMM = Math.round(<parameter>). Math.round works on the
        // float's bit pattern; it must yield R2BV(ROUND(...)), not bit-mask garbage.
        int d = Symbolicator.symbolic("diameterInMM", 325);
        float radius = d / 2.0f;
        int back = Math.round(radius * 2.0f);
        Expression e = Symbolicator.getExpression(back);
        System.out.println("[probe] round-trip expression: " + e);
        if (back > 329) {
            System.out.println("[probe] round-trip branch taken");
        }
        String pc = pc();
        System.out.println("[probe] mathRoundRoundTripStaysSymbolic PC: " + pc);
        Assertions.assertNotNull(e, "Math.round result must stay symbolic");
        Assertions.assertTrue(e.toString().contains("ROUND"), e.toString());
        Assertions.assertFalse(pc.contains("&"), "no bit-pattern constraints from Math.round internals: " + pc);
    }

    @Test
    void libraryCompareKeepsThePredicate() {
        // MultiModelOCL compares numbers via Double.compare(a, b) < 0; the JDK's -1/0/1
        // result must carry the comparison (masks + CmpDifference).
        double r = Symbolicator.symbolic("r_double", 162.5);
        if (Double.compare(r * r, 26000.0) < 0) {
            System.out.println("[probe] Double.compare branch taken");
        }
        String pc = pc();
        System.out.println("[probe] libraryCompareKeepsThePredicate PC: " + pc);
        Assertions.assertTrue(pc.contains("(r_double*r_double)") && pc.contains("26000.0"), pc);
    }
}
