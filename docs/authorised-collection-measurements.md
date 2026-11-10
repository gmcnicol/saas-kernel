# Collection measurements (#70)

25 October 2026; Java 25.0.4, Cedar Java 4.2.3, PostgreSQL 18-alpine Docker,
local shared development machine. Public `AuthorisedReads` boundary; generated
Network binding, real non-owner FORCE-RLS SQL, current source/index revision reads,
real Cedar, authority fences, JSON byte accounting and transaction cleanup.
Five warmups, twenty sequential samples per shape/mode; no concurrent benchmark
load. Percentiles are observed samples, **not** performance targets or a speedup
claim. The human-approved `bounded-current-api` permits these single calls.

Baseline is existing `many`, not the obsolete #67 sketch. Baseline evaluates N
records. Indexed collections release N and evaluate N+1 authorised records to prove
continuation. Indexed mode also checks the registered identifier ordering path:
one scope-level query call and one identifier leaf call per candidate. Baseline
`many` has no such query guarantees. One selected leaf is permitted;
the second selected leaf is ordinarily denied/redacted. Both still cost a decision.
Each admitted successful decision maps to one existing synchronous SDK/JNI call.
Index calls below are measured operator-meter deltas; baseline calls follow the
same executed record/selected-leaf loop, N × (1 + leaves), not a native profiler.
There is no batch API, parallel/native bridge or cross-request authority cache.

SQL counts are actual PostgreSQL logged statement executions in one additional
measurement operation after warmup, not JDBC method estimates or measured network
packet/round-trip counts. They include transaction/savepoint/scope restoration,
two authoritative revision SQL reads, and cursor fetches (`fetchSize=1`). This
Application-owned conservative fetch size explains the linear physical fetch cost;
Kernel does not claim to solve it. Pooled-connection housekeeping can add statements
on other runs. Logging was enabled only in the disposable synthetic benchmark
container; production meters contain no SQL or payloads.

| N | Leaves | many JNI | indexed JNI | many SQL | indexed SQL | many p50/p95 ms | indexed p50/p95 ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 2 | 7 | 18 | 19 | 8.427 / 10.925 | 20.935 / 23.363 |
| 8 | 1 | 16 | 28 | 25 | 26 | 46.284 / 53.237 | 73.297 / 86.899 |
| 32 | 1 | 64 | 100 | 49 | 50 | 146.566 / 165.082 | 219.590 / 245.882 |
| 96 | 1 | 192 | 292 | 113 | 114 | 419.448 / 449.988 | 643.146 / 687.175 |
| 1 | 2 | 3 | 9 | 18 | 19 | 8.450 / 9.445 | 25.073 / 28.031 |
| 8 | 2 | 24 | 37 | 25 | 26 | 51.492 / 66.259 | 81.057 / 89.132 |
| 32 | 2 | 96 | 133 | 49 | 50 | 218.120 / 236.370 | 292.454 / 313.676 |
| 96 | 2 | 288 | 389 | 113 | 114 | 649.135 / 723.501 | 857.216 / 916.079 |

Indexed paging adds a lookahead, ordering permission and token/index validation,
not fewer SDK calls. It is slower in this measured comparison. Earlier lower
individual medians were host/JIT/noise observations, **not evidence of a gain**.
Additional query paths cost one scope call and one leaf call per candidate;
decision ceilings still apply.
The 4,224 decision ceiling is an admission bound, not a promise it fits 5 seconds:
deadline exhaustion discards the whole operation. SQL/resolver/callback/native
calls must return to a checkpoint; no hard preemption or physical constant time.

Contrasting acceptance fixtures also traverse 300 Network records, 380 practice
staff records and 180 assigned-client records in bounded pages. Adding 10,000 hidden
Network rows leaves authorised page shape/count/continuation presence and 25
admitted calls (one ordering scope check; five records plus lookahead, ordering
leaf and two selected output leaves) unchanged. This
proves the authored authorised-relation contract's logical work invariance, not
arbitrary SQL/index correctness or hidden-row-independent database/network timing.

## Reproduce

```bash
export JAVA_HOME=/home/gareth/.sdkman/candidates/java/25.0.4-tem
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -pl applications/keep-in-touch-crm -am test \
  -Dtest=CollectionReadBenchmark#measure -Dsurefire.failIfNoSpecifiedTests=false
```

`CollectionReadBenchmark` is deliberately outside the default `*Tests` suite;
there are no skipped benchmark tests or timing assertions in clean verify.
Docker is required. Samples print `COLLECTION_BENCH`; no application payload is
printed. Earlier intermediate complete-raw-scan measurements are superseded:
that metadata operation was removed because work could reveal hidden density.
