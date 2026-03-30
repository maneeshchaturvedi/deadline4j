package io.deadline4j;

/**
 * Priority classification for a downstream service call.
 *
 * <ul>
 *   <li>{@link #REQUIRED} — the call is mandatory; failure should propagate.
 *   <li>{@link #OPTIONAL} — the call can be skipped when budget is insufficient.
 * </ul>
 */
public enum ServicePriority {

    /** The call is mandatory; failure should propagate. */
    REQUIRED,

    /** The call can be skipped when budget is insufficient. */
    OPTIONAL
}
