package io.github.gmcnicol.kernel.contract;

import static org.assertj.core.api.Assertions.*;
import io.github.gmcnicol.kernel.application.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Public final-release seam, real source/authority fixtures and recording external sinks only. */
public interface AuthorisedDisclosureContract<P> {
    enum Change { REVOKE, AUTHORITY, AUTHORITY_VALUES, VALUE, IDENTITY, WITHDRAW, REVISION, DEPENDENCY,
        ADD_CONTRIBUTOR, SECOND_IDENTITY, SECOND_VALUE, EXPIRED_AUTHORITY, MALFORMED_AUTHORITY, MISSING_AUTHORITY }
    record Fixture<P>(ReadRequest<P> request, ReadSource<P> source, Consumer<Change> change, String json) {}
    AuthorisedDisclosures disclosures();
    Fixture<P> disclosureFixture();
    void advanceDisclosureClock(long seconds);
    void inDisclosureTransaction(Runnable work);
    default AuthorisedDisclosures.Formatter<P> json() {
        return (views, out) -> out.write(new tools.jackson.databind.json.JsonMapper().writeValueAsBytes(views));
    }
    default String text(DisclosureBytes bytes) { return new String(bytes.copy(), StandardCharsets.UTF_8); }

    @org.junit.jupiter.api.TestFactory default java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> lineageRejectsDifferentTrustedReleaseContext() {
        return java.util.stream.Stream.of("purpose", "tenant", "principal", "scope", "fields", "bundle", "policy", "missing")
                .map(mode -> org.junit.jupiter.api.DynamicTest.dynamicTest(mode, () -> {
                    var f = disclosureFixture(); var r = f.request();
                    var prepared = disclosures().prepare(r, f.source(), "export", Duration.ofSeconds(30));
                    var current = mode.equals("missing") ? null : new ReadRequest<P>(
                            mode.equals("tenant") ? r.tenantId() + "-other" : r.tenantId(),
                            mode.equals("principal") ? new Principal(r.principal().type(), "other") : r.principal(),
                            mode.equals("scope") ? otherScope(r.scope()) : r.scope(), r.type(),
                            mode.equals("fields") ? Set.of() : r.fields(),
                            mode.equals("bundle") ? "stale" : r.bundleId(), mode.equals("policy") ? "stale" : r.bundleChecksum(), r.limits());
                    var sink = new ArrayList<DisclosureBytes>();
                    assertThatThrownBy(() -> disclosures().send(prepared, current, mode.equals("purpose") ? "assistant" : "export", f.source(), json(), sink::add))
                            .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
                    assertThat(sink).isEmpty();
                }));
    }
    private static <I> TypedSubject<I> otherScope(TypedSubject<I> scope) {
        return new TypedSubject<>(scope.type(), scope.type().fromExternalId("other"));
    }

    @Test default void lifetimeIsBoundedAndExpiryIsCheckedAfterFormatting() {
        var f = disclosureFixture();
        for (var lifetime : Arrays.asList(null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(301))) {
            assertThatThrownBy(() -> disclosures().prepare(f.request(), f.source(), "export", lifetime))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
        for (var purpose : Arrays.asList(null, "", " ", "private\ncontext", "x".repeat(129))) {
            assertThatThrownBy(() -> disclosures().prepare(f.request(), f.source(), purpose, Duration.ofSeconds(30)))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
        var prepared = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
        var sink = new ArrayList<DisclosureBytes>();
        assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "export", f.source(), (views, out) -> {
            json().format(views, out); advanceDisclosureClock(30);
        }, sink::add)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(sink).isEmpty();
    }

    @Test default void releaseRejectsCallerTransactionsAndNeverKeepsOneAcrossNetworkIO() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
        var sink = new ArrayList<DisclosureBytes>();
        inDisclosureTransaction(() -> {
            assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "export", f.source(), json(), sink::add))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
            assertThatThrownBy(() -> disclosures().derive(prepared, f.request(), "export", f.source(), json(), (bytes, out) -> sink.add(bytes)))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        });
        assertThat(sink).isEmpty();
        disclosures().send(prepared, f.request(), "export", f.source(), json(), bytes -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()).isEmpty();
            sink.add(bytes);
        });
        assertThat(sink).hasSize(1);
    }

    @Test default void staleContributingSourcesNeverReachEvenTheApplicationFormatter() {
        for (var change : List.of(Change.VALUE, Change.IDENTITY, Change.WITHDRAW, Change.REVISION, Change.AUTHORITY)) {
            var f = disclosureFixture();
            var prepared = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
            f.change().accept(change);
            var formatted = new ArrayList<String>(); var sink = new ArrayList<DisclosureBytes>();
            assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "export", f.source(), (views, out) -> {
                formatted.add("called"); json().format(views, out);
            }, sink::add)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
            assertThat(formatted).as("do not replace original lineage: %s", change).isEmpty();
            assertThat(sink).isEmpty();
        }
    }

    @Test default void effectiveValuesRemainBoundEvenWhenResolverIncorrectlyReusesItsRevision() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
        f.change().accept(Change.AUTHORITY_VALUES);
        var sink = new ArrayList<DisclosureBytes>();
        assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "export", f.source(), json(), sink::add))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(sink).isEmpty();
    }

    @Test default void assistantAndSeparateExportUseOnlyImmutableFilteredViewsAndOpaqueHandles() throws Exception {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
        var sent = new ArrayList<String>();
        var response = disclosures().derive(prepared, f.request(), "assistant", f.source(), (views, out) -> {
            assertThatThrownBy(views::clear).isInstanceOf(UnsupportedOperationException.class);
            json().format(views, out);
        }, (context, out) -> {
            sent.add(text(context));
            Arrays.fill(context.copy(), (byte) 'X');
            assertThat(text(context)).isEqualTo(f.json());
            out.write("safe answer".getBytes(StandardCharsets.UTF_8));
        });
        disclosures().send(response, f.request(), "assistant", f.source(), bytes -> sent.add(text(bytes)));
        assertThat(sent).containsExactly(f.json(), "safe answer");
        var export = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
        disclosures().send(export, f.request(), "export", f.source(), json(), bytes -> sent.add(text(bytes)));
        assertThat(sent.getLast()).isEqualTo(f.json());
        var mapper = new tools.jackson.databind.json.JsonMapper();
        assertThat(mapper.writeValueAsString(prepared)).isEqualTo("\"PreparedDisclosure[redacted]\"");
        assertThat(mapper.writeValueAsString(response)).isEqualTo("\"DerivedDisclosure[redacted]\"");
        assertThat(prepared.toString()).isEqualTo("PreparedDisclosure[redacted]");
        assertThat(response.toString()).isEqualTo("DerivedDisclosure[redacted]");
    }

    @Test default void replayAndBoundedChunksAlwaysRevalidateAndAlreadyDeliveredBytesRemainDelivered() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
        var delivered = new ArrayList<String>();
        disclosures().send(prepared, f.request(), "export", f.source(), json(), bytes -> delivered.add(text(bytes)));
        disclosures().send(prepared, f.request(), "export", f.source(), json(), bytes -> {
            delivered.add(text(bytes)); f.change().accept(Change.REVOKE);
        });
        assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "export", f.source(), json(), bytes -> delivered.add(text(bytes))))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(delivered).containsExactly(f.json(), f.json());
    }

    @org.junit.jupiter.api.TestFactory default java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> bufferedAnswersAreNeverPromotedToNewSourceLineage() {
        return java.util.stream.Stream.of(Change.VALUE, Change.IDENTITY, Change.WITHDRAW, Change.REVISION, Change.AUTHORITY, Change.DEPENDENCY)
                .map(change -> org.junit.jupiter.api.DynamicTest.dynamicTest(change.name(), () -> {
                    var f = disclosureFixture();
                    var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
                    var response = disclosures().derive(prepared, f.request(), "assistant", f.source(), json(), (context, out) -> {
                        assertThat(text(context)).isEqualTo(f.json());
                        f.change().accept(change); out.write("old answer".getBytes(StandardCharsets.UTF_8));
                    });
                    var delivered = new ArrayList<DisclosureBytes>();
                    assertThatThrownBy(() -> disclosures().send(response, f.request(), "assistant", f.source(), delivered::add))
                            .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
                    assertThat(delivered).isEmpty();
                }));
    }

    @Test default void failedDependenciesFormattingProvidersAndSinksNeverCreateUnsafeRetriesOrFallbacks() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
        var delivered = new ArrayList<DisclosureBytes>();
        assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "assistant", f.source(), (views, out) -> {
            json().format(views, out); throw new IllegalStateException("PRIVATE-FORMATTER");
        }, delivered::add)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
        assertThatThrownBy(() -> disclosures().derive(prepared, f.request(), "assistant", f.source(), json(), (context, out) -> {
            out.write("PRIVATE-PARTIAL-ANSWER".getBytes(StandardCharsets.UTF_8)); throw new IllegalStateException("PRIVATE-PROVIDER");
        })).isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
        assertThat(delivered).isEmpty();
        assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "assistant", f.source(), json(), bytes -> {
            delivered.add(bytes); throw new IllegalStateException("PRIVATE-SINK");
        })).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(delivered).hasSize(1); // Bytes may already have escaped. No automatic retry/rollback fiction.
        f.change().accept(Change.DEPENDENCY);
        assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "assistant", f.source(), json(), delivered::add))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(delivered).hasSize(1);
    }

    @Test default void outputBudgetsAreStickyAndRetainedWritersCannotMutateBufferedAnswers() {
        var f = disclosureFixture(); var r = f.request();
        var request = new ReadRequest<>(r.tenantId(), r.principal(), r.scope(), r.type(), r.fields(), r.bundleId(), r.bundleChecksum(), new ReadLimits(128, 32, 128, 5000));
        var prepared = disclosures().prepare(request, f.source(), "assistant", Duration.ofSeconds(30));
        var delivered = new ArrayList<DisclosureBytes>();
        assertThatThrownBy(() -> disclosures().send(prepared, request, "assistant", f.source(), (views, out) -> {
            try { out.write(new byte[129]); } catch (AuthorisationDeniedException ignored) { }
        }, delivered::add)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThatThrownBy(() -> disclosures().derive(prepared, request, "assistant", f.source(), json(), (context, out) -> {
            try { out.write(new byte[129]); } catch (AuthorisationDeniedException ignored) { }
        })).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(delivered).isEmpty();
        var retained = new ArrayList<java.io.OutputStream>();
        var response = disclosures().derive(prepared, request, "assistant", f.source(), json(), (context, out) -> {
            retained.add(out); out.write("answer".getBytes(StandardCharsets.UTF_8));
        });
        try { retained.getFirst().write("modified".getBytes(StandardCharsets.UTF_8)); } catch (Exception failure) { throw new AssertionError(failure); }
        disclosures().send(response, request, "assistant", f.source(), bytes -> assertThat(text(bytes)).isEqualTo("answer"));
    }

    @Test default void fabricatedOrMissingHandlesAndSourcesFailClosed() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
        for (var handle : Arrays.asList(null, new AuthorisedDisclosures.Prepared<P>() {})) {
            assertThatThrownBy(() -> disclosures().send(handle, f.request(), "export", f.source(), json(), bytes -> fail("no send")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
        for (var handle : Arrays.asList(null, new AuthorisedDisclosures.Derived<P>() {})) {
            assertThatThrownBy(() -> disclosures().send(handle, f.request(), "export", f.source(), bytes -> fail("no send")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
        assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "export", null, json(), bytes -> fail("no send")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThatThrownBy(() -> disclosures().prepare(null, f.source(), "export", Duration.ofSeconds(30)))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test default void preparedContextRetentionHasExplicitMetadataBounds() {
        var f = disclosureFixture(); var r = f.request();
        var oversized = new ReadRequest<>(r.tenantId(), new Principal(r.principal().type(), "x".repeat(4097)),
                r.scope(), r.type(), r.fields(), r.bundleId(), r.bundleChecksum(), r.limits());
        assertThatThrownBy(() -> disclosures().prepare(oversized, f.source(), "export", Duration.ofSeconds(30)))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        var oversizedRevision = new ReadSource<P>() {
            public String revision(java.sql.Connection c) { return "x".repeat(4097); }
            public void select(java.sql.Connection c, Consumer<ReadCandidate<P>> sink) throws Exception { f.source().select(c, sink); }
        };
        assertThatThrownBy(() -> disclosures().prepare(r, oversizedRevision, "export", Duration.ofSeconds(30)))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test default void everyContributorIdentityAndSelectedValueIsCheckedNotJustFirstOrJsonEquality() {
        for (var change : List.of(Change.SECOND_IDENTITY, Change.SECOND_VALUE)) {
            var f = disclosureFixture(); f.change().accept(Change.ADD_CONTRIBUTOR);
            var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
            var answer = disclosures().derive(prepared, f.request(), "assistant", f.source(), (views, out) -> {
                assertThat(views).hasSize(2); json().format(views, out);
            }, (context, out) -> out.write("answer using both".getBytes(StandardCharsets.UTF_8)));
            f.change().accept(change); // Same authoritative revision; identity change keeps selected JSON identical.
            assertThatThrownBy(() -> disclosures().send(answer, f.request(), "assistant", f.source(), bytes -> fail("no send")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
    }

    @Test default void missingMalformedAndExpiredCurrentContextsNeverMintOrReleaseAuthority() {
        for (var change : List.of(Change.EXPIRED_AUTHORITY, Change.MALFORMED_AUTHORITY, Change.MISSING_AUTHORITY)) {
            var f = disclosureFixture();
            var prepared = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
            f.change().accept(change);
            assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "export", f.source(), json(), bytes -> fail("no send")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
            assertThatThrownBy(() -> disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30)))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
    }

    default void checkDisclosureLogBoundary(java.util.function.Supplier<String> logs) {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
        var response = disclosures().derive(prepared, f.request(), "assistant", f.source(), json(), (context, out) -> {
            assertThat(text(context)).isEqualTo(f.json()); out.write("safe answer".getBytes(StandardCharsets.UTF_8));
        });
        disclosures().send(response, f.request(), "assistant", f.source(), bytes -> assertThat(text(bytes)).isEqualTo("safe answer"));
        assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "assistant", f.source(), (views, out) -> {
            throw new IllegalStateException("PRIVATE-ERROR-VALUE");
        }, bytes -> fail("no send"))).isExactlyInstanceOf(AuthorisationDeniedException.class)
                .hasMessage("Authorisation denied").hasNoCause();
        assertThat(logs.get()).doesNotContain("PRIVATE-IDENTITY", "PRIVATE-NOTE", "PRIVATE-ERROR-VALUE", "STAFF-SECRET", "OTHER-REF", "CLIENT-REF", "FOREIGN-SECRET");
    }

    @Test default void sourceChangesDuringFormattingDiscardEveryBoundedRelease() {
        for (var change : List.of(Change.VALUE, Change.IDENTITY, Change.WITHDRAW, Change.REVISION)) {
            var f = disclosureFixture();
            var prepared = disclosures().prepare(f.request(), f.source(), "export", Duration.ofSeconds(30));
            var sink = new ArrayList<DisclosureBytes>();
            assertThatThrownBy(() -> disclosures().send(prepared, f.request(), "export", f.source(), (views, out) -> {
                json().format(views, out); f.change().accept(change);
            }, sink::add)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
            assertThat(sink).isEmpty();
        }
    }

    @Test default void expiredProviderResultsAndDifferentPurposeCannotReachUserSinks() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
        var response = disclosures().derive(prepared, f.request(), "assistant", f.source(), json(), (context, out) -> out.write("answer".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> disclosures().send(response, f.request(), "export", f.source(), bytes -> fail("no send")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        advanceDisclosureClock(30);
        assertThatThrownBy(() -> disclosures().send(response, f.request(), "assistant", f.source(), bytes -> fail("no send")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test default void slowPreparationCannotReturnAlreadyExpiredLineage() {
        var f = disclosureFixture();
        var slow = new ReadSource<P>() {
            public String revision(java.sql.Connection c) throws Exception { return f.source().revision(c); }
            public void select(java.sql.Connection c, Consumer<ReadCandidate<P>> sink) throws Exception {
                f.source().select(c, sink); advanceDisclosureClock(30);
            }
        };
        assertThatThrownBy(() -> disclosures().prepare(f.request(), slow, "export", Duration.ofSeconds(30)))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test default void sourceDependenciesAndMissingRevisionNeverReleaseBufferedProviderResults() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
        var response = disclosures().derive(prepared, f.request(), "assistant", f.source(), json(), (context, out) -> out.write("answer".getBytes(StandardCharsets.UTF_8)));
        for (var mode : List.of("missing-revision", "sql-error", "after-candidate")) {
            var broken = new ReadSource<P>() {
                public String revision(java.sql.Connection c) throws Exception {
                    return mode.equals("missing-revision") ? null : f.source().revision(c);
                }
                public void select(java.sql.Connection c, Consumer<ReadCandidate<P>> sink) throws Exception {
                    if (mode.equals("sql-error")) {
                        try (var q = c.prepareStatement("SELECT PRIVATE_MISSING_COLUMN FROM PRIVATE_MISSING_TABLE")) { q.execute(); }
                    } else {
                        f.source().select(c, sink); throw new IllegalStateException("PRIVATE-SOURCE-FAILURE");
                    }
                }
            };
            assertThatThrownBy(() -> disclosures().send(response, f.request(), "assistant", broken, bytes -> fail("no send")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
        }
    }

    @Test default void slowFormattingCannotBypassTheProviderFence() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
        var provider = new ArrayList<DisclosureBytes>();
        assertThatThrownBy(() -> disclosures().derive(prepared, f.request(), "assistant", f.source(), (views, out) -> {
            json().format(views, out);
            f.change().accept(Change.REVOKE);
        }, (bytes, out) -> provider.add(bytes))).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(provider).isEmpty();
    }

    @Test default void bufferedProviderResponseCannotOutliveOriginalSourceAuthority() {
        var f = disclosureFixture();
        var prepared = disclosures().prepare(f.request(), f.source(), "assistant", Duration.ofSeconds(30));
        var providerContext = new ArrayList<String>();
        var response = disclosures().derive(prepared, f.request(), "assistant", f.source(), json(), (context, out) -> {
            providerContext.add(text(context));
            f.change().accept(Change.REVOKE);
            out.write("buffered provider answer".getBytes(StandardCharsets.UTF_8));
        });
        assertThat(providerContext).containsExactly(f.json());
        var user = new ArrayList<DisclosureBytes>();
        assertThatThrownBy(() -> disclosures().send(response, f.request(), "assistant", f.source(), user::add))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
        assertThat(user).isEmpty();
    }
}
