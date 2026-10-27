package io.github.gmcnicol.kernel.contract;

import static org.assertj.core.api.Assertions.*;
import io.github.gmcnicol.kernel.application.*;
import org.junit.jupiter.api.Test;

/** Reusable public read/collection contract; each Application owns SQL, RLS, policy and grants. */
public interface AuthorisationContract<P> extends AuthorisedDisclosureContract<P> {
    record ExpectedField<P, V>(ReadPath<P, V> path, ReadValue<V> value) {
        public void check(AuthorisedRecord<P> record) { assertThat(record.get(path)).isEqualTo(value); }
    }
    record ReadFixture<P>(ReadRequest<P> request, ReadSource<P> source, TypedSubject<?> resource, TypedCapability<?> capability,
            EffectiveCapabilityContract.ApplicationGrants grants, EffectiveCapabilityContract.TestClock clock,
            String json, java.util.List<ExpectedField<P, ?>> fields) {
        public ReadFixture { fields = java.util.List.copyOf(fields); }
    }
    record CollectionFixture<P>(ReadRequest<P> request, AuthorisedIndex<P> index,
            Runnable hiddenOnly, java.util.List<String> pages, ReadPath<P, ?> deniedQuery) {
        public CollectionFixture { pages = java.util.List.copyOf(pages); }
    }
    AuthorisedReads reads();
    ReadFixture<P> readFixture();
    CollectionFixture<P> collectionFixture();
    void addOtherScopeProvenance(ReadFixture<P> fixture, int copies);
    TypedCapability<?> unrelatedCapability();
    TypedCapability<?> capabilityWithRemaining(int remaining);
    io.micrometer.core.instrument.MeterRegistry meters();

    @Test default void deniedSearchAndSortRejectBeforeAnyIndexSelection() {
        var f = collectionFixture(); var r = f.request();
        var noOutput = new ReadRequest<>(r.tenantId(), r.principal(), r.scope(), r.type(), java.util.Set.<ReadPath<P, ?>>of(),
                r.bundleId(), r.bundleChecksum(), r.limits());
        for (var operation : java.util.List.of("search", "sort")) {
            var privateQuery = new AuthorisedIndex<P>() {
                public String queryIdentity() { return operation + "-private-field-v1"; }
                public java.util.Set<ReadPath<P, ?>> queryFields() { return java.util.Set.of(f.deniedQuery()); }
                public State current(java.sql.Connection c, Binding<P> binding) { throw new AssertionError("denied query reached index state"); }
                public void select(java.sql.Connection c, Binding<P> binding, long offset, int limit,
                        java.util.function.Consumer<ReadCandidate<P>> sink) { throw new AssertionError("denied query reached selection"); }
            };
            assertThatThrownBy(() -> reads().collection(noOutput, privateQuery, CollectionOptions.page(1)))
                    .isInstanceOfSatisfying(CollectionReadException.class, failure -> assertThat(failure.reason()).isEqualTo(CollectionReadException.Reason.DENIED))
                    .hasMessage("Collection read failed: DENIED").hasNoCause();
        }
    }

    @Test default void hiddenOnlyChangesPreservePublicShapeCountContinuationAndLogicalWork() {
        var f = collectionFixture();
        double before = collectionDecisions();
        var first = reads().collection(f.request(), f.index(), CollectionOptions.page(1));
        double work = collectionDecisions() - before;
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(first.records())).isEqualTo(f.pages().getFirst());
        assertThat(first.exactCount()).hasValue(f.pages().size());
        assertThat(first.continuation()).isPresent();
        f.hiddenOnly().run();
        before = collectionDecisions();
        var again = reads().collection(f.request(), f.index(), CollectionOptions.page(1));
        assertThat(collectionDecisions() - before).isEqualTo(work).isPositive();
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(again.records())).isEqualTo(f.pages().getFirst());
        assertThat(again.exactCount()).hasValue(f.pages().size());
        assertThat(again.continuation()).isPresent();
        // Resume the ORIGINAL token after hidden mutations; encryption is random, so never compare token bytes.
        var token = first.continuation();
        for (int page = 1; page < f.pages().size(); page++) {
            var next = reads().collection(f.request(), f.index(), new CollectionOptions(1, 4224, () -> false, token));
            assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(next.records())).isEqualTo(f.pages().get(page));
            assertThat(next.exactCount()).hasValue(f.pages().size());
            token = next.continuation();
        }
        assertThat(token).isEmpty();
    }
    @Test default void changedSourceAndPolicyInvalidateTheOriginalCollectionContinuation() {
        var f = collectionFixture();
        var first = reads().collection(f.request(), f.index(), CollectionOptions.page(1));
        for (var mode : java.util.List.of("source", "policy")) {
            var changed = new AuthorisedIndex<P>() {
                public String queryIdentity() { return f.index().queryIdentity(); }
                public java.util.Set<ReadPath<P, ?>> queryFields() { return f.index().queryFields(); }
                public State current(java.sql.Connection c, Binding<P> b) throws Exception {
                    var state = f.index().current(c, b);
                    return new State(mode.equals("source") ? "new-source" : state.sourceRevision(), state.indexRevision(),
                            mode.equals("policy") ? "new-current-policy" : state.bundleChecksum(), state.authorityRevision(), state.exactCount());
                }
                public void select(java.sql.Connection c, Binding<P> b, long offset, int limit,
                        java.util.function.Consumer<ReadCandidate<P>> sink) { throw new AssertionError("stale continuation reached selection"); }
            };
            assertThatThrownBy(() -> reads().collection(f.request(), changed, new CollectionOptions(1, 4224, () -> false, first.continuation())))
                    .isInstanceOfSatisfying(CollectionReadException.class, failure -> assertThat(failure.reason()).isEqualTo(CollectionReadException.Reason.STALE))
                    .hasMessage("Collection read failed: STALE").hasNoCause();
        }
    }

    @Test default void rawCollectionMetadataRemainsUnsupportedRatherThanAnInferenceFallback() {
        var f = readFixture();
        assertThatThrownBy(() -> reads().collection(f.request(), f.source(), CollectionOptions.page(1)))
                .isInstanceOfSatisfying(CollectionReadException.class, failure -> assertThat(failure.reason()).isEqualTo(CollectionReadException.Reason.UNSUPPORTED))
                .hasMessage("Collection read failed: UNSUPPORTED").hasNoCause();
    }

    private double collectionDecisions() {
        var summary = meters().find("kernel.read.collection.decisions").tag("mode", "index").summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    @Test default void samePersonProvenanceNeverBridgesResourceScopes() {
        var f = readFixture(); f.grants().grant(f.request().tenantId(), "paid", java.time.Instant.MAX);
        var random = new java.util.Random(7201);
        for (int example = 0; example < 6; example++) {
            addOtherScopeProvenance(f, 1 + random.nextInt(20));
            var records = reads().many(f.request(), f.source());
            assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(records)).isEqualTo(f.json());
            assertThat(records).hasSize(1);
            f.fields().forEach(field -> field.check(records.getFirst()));
        }
    }

    @org.junit.jupiter.api.TestFactory default java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> malformedReadAuthorityFailsClosed() {
        return java.util.Arrays.stream(EffectiveCapabilityContract.Corruption.values()).map(corruption ->
                org.junit.jupiter.api.DynamicTest.dynamicTest(corruption.name(), () -> {
                    var f = readFixture();
                    f.grants().grant(f.request().tenantId(), "paid", java.time.Instant.MAX);
                    f.grants().corrupt(f.request().tenantId(), corruption);
                    assertThatThrownBy(() -> reads().many(f.request(), f.source())).isExactlyInstanceOf(AuthorisationDeniedException.class)
                            .hasMessage("Authorisation denied").hasNoCause();
                }));
    }

    @Test default void unregisteredFieldAndResourceDescriptorsCannotBorrowGeneratedNames() {
        var f = readFixture(); var r = f.request();
        f.grants().grant(r.tenantId(), "paid", java.time.Instant.MAX);
        var path = r.fields().iterator().next();
        var forged = new ReadRequest<>(r.tenantId(), r.principal(), r.scope(), r.type(), java.util.Set.<ReadPath<P, ?>>of(forge(path)),
                r.bundleId(), r.bundleChecksum(), r.limits());
        assertThatThrownBy(() -> reads().many(forged, f.source())).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        var wrongResource = new ReadSource<P>() {
            public String revision(java.sql.Connection c) throws Exception { return f.source().revision(c); }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<P>> sink) throws Exception {
                f.source().select(c, candidate -> sink.accept(new ReadCandidate<>(candidate.tenantId(), candidate.scope(),
                        forge(f.resource()), candidate.value())));
            }
        };
        assertThatThrownBy(() -> reads().many(r, wrongResource)).isExactlyInstanceOf(AuthorisationDeniedException.class)
                .hasMessage("Authorisation denied").hasNoCause();
    }
    private static <P, V> ReadPath<P, V> forge(ReadPath<P, V> path) {
        return new ReadPath<>(path.qualifiedName(), path.path(), path.read(), path.scalar());
    }
    private static <I> TypedSubject<I> forge(TypedSubject<I> subject) {
        var type = subject.type();
        return new TypedSubject<>(new SubjectType<>(type.qualifiedName(), type.javaType(), type::externalId, type::fromExternalId), subject.id());
    }

    @Test default void explicitNegativePermissionsOverrideMatchingRecordAndFieldPermits() {
        var f = readFixture(); var r = f.request();
        f.grants().grant(r.tenantId(), "paid", java.time.Instant.MAX);
        f.grants().replace(r.tenantId(), capabilityWithRemaining(0));
        assertThat(reads().many(r, f.source())).isEmpty();
        f.grants().replace(r.tenantId(), capabilityWithRemaining(1));
        var records = reads().many(r, f.source());
        assertThat(records).hasSize(1);
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(records)).isEqualTo("[{}]");
        r.fields().forEach(path -> assertThat(records.getFirst().get(path)).isEqualTo(ReadValue.hidden()));
        f.grants().replace(r.tenantId(), f.capability());
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(reads().many(r, f.source()))).isEqualTo(f.json());
    }

    /** Server-only categories; never a per-record external explanation. */
    enum Denial { AUTHORITY, RECORD_POLICY, FIELD_POLICY }
    private double denials(Denial category) {
        var counter = meters().find("kernel.read.denials").tag("category", category.name().toLowerCase(java.util.Locale.ROOT)).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test default void safeDiagnosticsDistinguishMissingAuthorityRecordPolicyAndFieldPolicy() {
        var f = readFixture(); var r = f.request();
        double before = denials(Denial.AUTHORITY);
        assertThatThrownBy(() -> reads().many(r, f.source())).isExactlyInstanceOf(AuthorisationDeniedException.class)
                .hasMessage("Authorisation denied").hasNoCause();
        assertThat(denials(Denial.AUTHORITY) - before).isEqualTo(1);
        f.grants().grant(r.tenantId(), "paid", java.time.Instant.MAX);
        before = denials(Denial.FIELD_POLICY);
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(reads().many(r, f.source()))).isEqualTo(f.json());
        assertThat(denials(Denial.FIELD_POLICY) - before).isGreaterThanOrEqualTo(1);
        before = denials(Denial.RECORD_POLICY);
        var outsider = new ReadRequest<>(r.tenantId(), new Principal(r.principal().type(), "not-a-member"), r.scope(),
                r.type(), r.fields(), r.bundleId(), r.bundleChecksum(), r.limits());
        assertThat(reads().many(outsider, f.source())).isEmpty();
        assertThat(denials(Denial.RECORD_POLICY) - before).isGreaterThanOrEqualTo(1);
        assertThat(meters().find("kernel.read.denials").meters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).hasSize(1);
            assertThat(meter.getId().getTag("category")).isIn("authority", "record_policy", "field_policy");
        });
    }

    @Test default void addingUnrelatedCapabilitiesNeverIncreasesDisclosure() {
        var random = new java.util.Random(72);
        for (int example = 0; example < 12; example++) {
            var f = readFixture(); var tenant = f.request().tenantId();
            boolean entitled = random.nextBoolean();
            if (entitled) f.grants().grant(tenant, random.nextBoolean() ? "paid" : "preview", java.time.Instant.MAX);
            if (entitled) assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(reads().many(f.request(), f.source())))
                    .isEqualTo(f.json());
            else assertThatThrownBy(() -> reads().many(f.request(), f.source())).isExactlyInstanceOf(AuthorisationDeniedException.class);
            f.grants().include(tenant, unrelatedCapability());
            if (entitled) assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(reads().many(f.request(), f.source())))
                    .isEqualTo(f.json());
            else assertThatThrownBy(() -> reads().many(f.request(), f.source()))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
        }
    }

    @Test default void independentGrantRevocationAndExpiryUseCurrentAuthority() {
        var f = readFixture(); var tenant = f.request().tenantId();
        f.grants().grant(tenant, "preview", java.time.Instant.MAX);
        f.grants().grant(tenant, "paid", java.time.Instant.MAX);
        f.grants().revoke(tenant, "paid");
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(reads().many(f.request(), f.source())))
                .isEqualTo(f.json());
        f.grants().revoke(tenant, "preview");
        assertThatThrownBy(() -> reads().many(f.request(), f.source()))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
        f.grants().grant(tenant, "preview", f.clock().instant().plusSeconds(1));
        f.grants().grant(tenant, "paid", f.clock().instant().plusSeconds(10));
        f.clock().advanceSeconds(2);
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(reads().many(f.request(), f.source()))).isEqualTo(f.json());
        f.grants().revoke(tenant, "paid");
        assertThatThrownBy(() -> reads().many(f.request(), f.source()))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test default void bufferedAnswersRespectIndependentGrantsExpiryAndLastRevocationWithoutRecallingBytes() {
        var f = readFixture(); var r = f.request();
        f.grants().grant(r.tenantId(), "preview", f.clock().instant().plusSeconds(1));
        f.grants().grant(r.tenantId(), "paid", java.time.Instant.MAX);
        var prepared = disclosures().prepare(r, f.source(), "assistant", java.time.Duration.ofSeconds(30));
        var answer = disclosures().derive(prepared, r, "assistant", f.source(), json(), (context, out) -> {
            assertThat(text(context)).isEqualTo(f.json()); out.write("answer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        f.clock().advanceSeconds(2);
        var delivered = new java.util.ArrayList<String>();
        disclosures().send(answer, r, "assistant", f.source(), bytes -> delivered.add(text(bytes)));
        f.grants().revoke(r.tenantId(), "paid");
        assertThatThrownBy(() -> disclosures().send(answer, r, "assistant", f.source(), bytes -> delivered.add(text(bytes))))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
        assertThat(delivered).containsExactly("answer");
    }

    @Test default void deliberatelyUnfilteredSqlIsContainedByNonOwnerForceRls() {
        var f = readFixture();
        f.grants().grant(f.request().tenantId(), "paid", java.time.Instant.MAX);
        var records = reads().many(f.request(), f.source());
        assertThat(records).hasSize(1);
        f.fields().forEach(field -> field.check(records.getFirst()));
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(records)).isEqualTo(f.json());
    }

    @Test default void paidAndPreviewHaveTheSameAuthorisedRepresentation() {
        var paid = readFixture();
        paid.grants().grant(paid.request().tenantId(), "paid", java.time.Instant.MAX);
        var preview = readFixture();
        preview.grants().grant(preview.request().tenantId(), "preview", java.time.Instant.MAX);
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(reads().many(paid.request(), paid.source())))
                .isEqualTo(paid.json()).isEqualTo(preview.json());
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(reads().many(preview.request(), preview.source())))
                .isEqualTo(paid.json());
    }
}
