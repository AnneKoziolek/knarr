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
        return PREFIXES != null;
    }

    /** True if a constraint produced at the current point should be recorded. */
    public static boolean allowed() {
        if (PREFIXES == null) {
            return true;
        }
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
