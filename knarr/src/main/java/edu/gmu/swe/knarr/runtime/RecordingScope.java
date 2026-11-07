package edu.gmu.swe.knarr.runtime;

/**
 * Optional scope for path-constraint recording (CoCoPath/UncertainTree, 2026-11-07).
 *
 * <p>Galette's listener fires for every branch on a tagged value in every instrumented
 * class, including the JDK and frameworks such as EMF and Vitruvius (change
 * notifications comparing old and new values, XMI serialization of ints, ...). For
 * concolic exploration of consistency-preservation rules only branches taken by the
 * rules themselves (and optionally by an evaluator) define paths.
 *
 * <p>If the system property {@code knarr.record.prefixes} is set (comma-separated class
 * name prefixes, e.g. {@code mir.,tools.vitruv.multimodelocl.}), a constraint is recorded
 * only when the nearest <em>application</em> frame on the stack matches one of them.
 * Frames of the JDK, Knarr, Galette, Green and the Xtend runtime library are treated as
 * transparent, so a JDK method called by a rule counts as the rule. Without the property
 * everything is recorded (Bell's original behaviour).
 */
public final class RecordingScope {
    private static final String[] PREFIXES = parse(System.getProperty("knarr.record.prefixes"));
    private static final String[] TRANSPARENT = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.",
        "edu.gmu.swe.knarr.", "edu.neu.ccs.prl.galette.", "za.ac.sun.cs.green.",
        "org.eclipse.xtext.xbase.lib."
    };
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final String SYMBOLICATOR = "edu.gmu.swe.knarr.runtime.Symbolicator";

    private RecordingScope() {}

    private static String[] parse(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String[] parts = s.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    public static boolean isActive() {
        return PREFIXES != null && STACKWALK;
    }

    /** True if a constraint produced at the current point should be recorded. */
    private static final java.util.concurrent.atomic.AtomicLong CALLS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ALLOWED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong NANOS = new java.util.concurrent.atomic.AtomicLong();

    /** Counters since start: checks, checks that allowed recording, time spent checking. */
    public static String stats() {
        return "scope checks=" + CALLS.get() + " allowed=" + ALLOWED.get() + " time=" + NANOS.get() / 1_000_000 + " ms";
    }

    /**
     * Stack walking is expensive in a Galette-instrumented JVM (measured 3.6 ms per check
     * on Vitruvius, 72k checks per commit). The default scope mechanism is therefore
     * Galette's instrumentation-time branch filter ({@code galette.symbolic.branchPrefixes},
     * see {@link #configureGalette()}); the stack-walking filter is only used if
     * {@code knarr.record.stackwalk=true} is set as well.
     */
    private static final boolean STACKWALK = Boolean.getBoolean("knarr.record.stackwalk");

    /**
     * If {@code galette.symbolic.branchPrefixes} is set, Galette emits branch hooks only in
     * matching application classes; branches inside the JDK are reported through the
     * library hooks, which are switched off here. Called when the listener is created.
     */
    public static void configureGalette() {
        String p = System.getProperty("galette.symbolic.branchPrefixes");
        if (p != null && !p.trim().isEmpty()) {
            edu.neu.ccs.prl.galette.internal.runtime.symbolic.SymbolicListener.setRecordLibraryBranches(false);
        }
    }

    public static boolean allowed() {
        if (PREFIXES == null || !STACKWALK) {
            return true;
        }
        long t = System.nanoTime();
        boolean ok = walk();
        NANOS.addAndGet(System.nanoTime() - t);
        CALLS.incrementAndGet();
        if (ok) {
            ALLOWED.incrementAndGet();
        }
        return ok;
    }

    private static boolean walk() {
        return WALKER.walk(frames -> {
            java.util.Iterator<StackWalker.StackFrame> it = frames.iterator();
            while (it.hasNext()) {
                String c = it.next().getClassName();
                // Constraints Symbolicator adds itself (variable domains/anchors) are always kept.
                if (c.equals(SYMBOLICATOR)) {
                    return true;
                }
                if (!startsWithAny(c, TRANSPARENT)) {
                    return startsWithAny(c, PREFIXES);
                }
            }
            return false;
        });
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
