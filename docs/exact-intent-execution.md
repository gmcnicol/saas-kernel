# Exact Intent execution

Use `Kernel.execute(...)` when one named Intent and Application-owned database changes must commit atomically. It accepts and completes only the supplied tenant-, Principal-, Action Offer-, Intent ID-, Candidate Payload-, and execution-time-bound request.

## Transaction requirement

The caller must already have an active transaction that participates in the Kernel datasource through the configured Spring transaction manager. `execute` does not open an independent transaction. It verifies Spring's strongest reliable participation contract: an actual active transaction, active synchronization, and a transaction-synchronized connection bound for the exact Kernel datasource. Spring does not expose a stable transaction-manager identity for comparison, so Kernel deliberately does not use brittle manager-object identity checks.

For example, put the Application write and exact execution in one Spring transaction and let any exception escape so Spring rolls everything back:

```java
@Transactional(transactionManager = "transactionManager")
ExactIntentCompletion applyOneAction(
        String tenantId,
        Principal principal,
        UUID actionOfferId,
        UUID intentId,
        TypedCandidatePayload<MyCandidate> candidate,
        Instant executedAt) {
    applicationJdbc.update("update application_record set ... where tenant_id = ?", tenantId);
    return kernel.execute(
            tenantId, principal, actionOfferId, intentId, candidate, executedAt);
}
```

The configured transaction manager must bind `applicationJdbc` and the Kernel to the same datasource connection. Merely activating another manager, or binding a datasource connection without a real datasource transaction, is rejected. Beware of Spring self-invocation: calling an `@Transactional` method through `this` does not pass through the transaction proxy.

`ExactIntentCompletion.COMPLETED` means execution finished inside the current transaction. It is **provisional** until the outer transaction commits. A later rollback removes the accepted Intent, Execution Receipts, Kernel execution changes, Application projection updates, and other Application writes made in that transaction. Other transactions, including asynchronous workers, cannot observe the exact Intent before commit.

## Replay and conflict

The caller-assigned Intent ID is an idempotency identity, not permission to replace a request:

- replaying the identical committed request returns `ALREADY_SUCCEEDED` and creates no duplicate Events or Application effect;
- changing bound request content, including the Candidate Payload or explicit execution time, under that Intent identity raises `IntentConflictException`;
- any outcome other than completion or an identical prior success fails the call. Do not catch that failure and then commit partial Application work.

## Exact execution is not queue processing

`execute` claims and completes only the named Intent. It never scans for or claims unrelated due work and must not be used to drain a queue.

Use `processNext(processedAt)` or `processDue(processedAt)` for ordinary asynchronous queue processing. Those operations select due work and retain their existing lease, retry, and worker behavior. Choose exact execution only when the caller needs one request to share an Application database transaction.

## External side effects

An open database transaction does not make remote calls transactional. Do not call an HTTP API, send a message, or perform another irreversible side effect merely because `execute` is running inside a transaction: a later database rollback cannot undo it. Record the required integration work transactionally in Application-owned data (for example, an outbox) and perform the remote call after commit with its own idempotency and retry policy.
