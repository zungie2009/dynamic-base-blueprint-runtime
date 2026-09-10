package org.blueprintruntime.rules;

import org.blueprintruntime.runtime.GenericRecord;

/**
 * The {@code eventContext} of {@code crossTableEventPropagation}: a mutation's type and
 * table, plus immutable typed before/after snapshots ({@code recordBefore} is null for
 * CREATE, {@code recordAfter} is null for DELETE).
 *
 * <p>{@code synthetic} distinguishes a real origin mutation (a live CREATE/UPDATE/DELETE,
 * or a directly dispatched acceptance-example event) from an event {@link RuleEngine}
 * itself re-enqueues after a coalesced cascade write. Same-table rules targeting a
 * synthetic event's own table are never re-matched against it: {@code rulesForTable}
 * already evaluated every rule declared against that table — including its own
 * same-table AFTER rules — as part of the very coalesced update that produced this
 * event, so re-matching them here would evaluate (and audit) them a second time for no
 * new input. Only cross-table {@code triggerBinding} rules propagate a synthetic event
 * onward to a different table.</p>
 */
public record MutationEvent(String eventType, String table, GenericRecord recordBefore, GenericRecord recordAfter, boolean synthetic) {

    /** A real origin mutation: a live CRUD write or a directly dispatched acceptance-example event. */
    public static MutationEvent origin(String eventType, String table, GenericRecord recordBefore, GenericRecord recordAfter) {
        return new MutationEvent(eventType, table, recordBefore, recordAfter, false);
    }

    /** An event {@link RuleEngine} re-enqueues after a coalesced cascade write, to propagate onward to other tables. */
    public static MutationEvent synthetic(String eventType, String table, GenericRecord recordBefore, GenericRecord recordAfter) {
        return new MutationEvent(eventType, table, recordBefore, recordAfter, true);
    }
}
