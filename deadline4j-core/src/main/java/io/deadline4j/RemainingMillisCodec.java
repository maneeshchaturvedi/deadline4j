package io.deadline4j;

/**
 * Default {@link DeadlineCodec} implementation that serializes deadlines
 * using remaining milliseconds.
 *
 * <p>Uses two headers:
 * <ul>
 *   <li>{@code X-Deadline-Remaining-Ms} — remaining time in milliseconds (required)</li>
 *   <li>{@code X-Deadline-Id} — correlation ID (optional)</li>
 * </ul>
 *
 * <p>Package-private — obtain via {@link DeadlineCodec#remainingMillis()}.
 */
final class RemainingMillisCodec implements DeadlineCodec {

    static final String HEADER_REMAINING_MS = "X-Deadline-Remaining-Ms";
    static final String HEADER_DEADLINE_ID = "X-Deadline-Id";

    @Override
    public <C> void inject(Deadline deadline, C carrier, CarrierSetter<C> setter) {
        setter.set(carrier, HEADER_REMAINING_MS, String.valueOf(deadline.remainingMillis()));
        deadline.id().ifPresent(id -> setter.set(carrier, HEADER_DEADLINE_ID, id));
    }

    @Override
    public <C> Deadline extract(C carrier, CarrierGetter<C> getter) {
        String remainingStr = getter.get(carrier, HEADER_REMAINING_MS);
        if (remainingStr == null || remainingStr.isEmpty()) {
            return null;
        }

        long remainingMs;
        try {
            remainingMs = Long.parseLong(remainingStr);
        } catch (NumberFormatException e) {
            return null;
        }

        if (remainingMs <= 0) {
            return null;
        }

        String id = getter.get(carrier, HEADER_DEADLINE_ID);
        return Deadline.fromRemainingMillis(remainingMs, id);
    }
}
