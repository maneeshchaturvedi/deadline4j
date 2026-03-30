package io.deadline4j;

/**
 * Controls whether deadline enforcement is active.
 *
 * <ul>
 *   <li>{@link #OBSERVE} — track and emit metrics, but do not enforce deadlines.
 *   <li>{@link #ENFORCE} — actively enforce deadlines (cancel, short-circuit).
 *   <li>{@link #DISABLED} — deadline processing is completely disabled.
 * </ul>
 */
public enum EnforcementMode {

    /** Track and emit metrics, but do not enforce deadlines. */
    OBSERVE,

    /** Actively enforce deadlines. */
    ENFORCE,

    /** Deadline processing is completely disabled. */
    DISABLED
}
