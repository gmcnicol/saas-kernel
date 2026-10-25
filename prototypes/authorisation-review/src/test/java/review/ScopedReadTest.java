package review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import review.ScopedRead.*;
import review.bindings.review.*;

@Testcontainers
class ScopedReadTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withInitScript("init.sql");
    static final Scope MEMBER = new Scope("t1", "member-a", "member", "network-a");
    static final Model<Contact> CONTACT = new Model<>(c -> new Identity(c.tenant(), c.network(), "review.Contact", c.id(), "", c.revision()),
            List.of(Leaf.nested(Contact.DETAILS, ContactDetails.DISPLAY_NAME),
                    Leaf.nested(Contact.DETAILS, ContactDetails.EMAIL),
                    Leaf.nested(Contact.DETAILS, ContactDetails.PRIVATE_NOTE)));
    final PostgresCedarRead reader = new PostgresCedarRead(ScopedReadTest::login,
            (scope, now) -> new Authority(scope, new EffectiveRead(true), now, now.plusSeconds(30), "authority-1"));

    @Test void networkMemberSeesOnlySelectedNestedLeavesDespiteWrongSqlAndSharedPerson() {
        // Application callback deliberately forgets BOTH tenant and Network predicates.
        var result = reader.read(MEMBER, CONTACT, ScopedReadTest::contacts);
        assertThat(result.stream().map(View::fields).toList()).containsExactly(
                Map.of("review.Contact.details.displayName", "Ada"));
        assertThat(result.toString()).doesNotContain("Ada", "secret", "same-human", "contact-b");
    }

    static final Model<Filing> FILING = new Model<>(f -> new Identity(f.tenant(), f.practice(), "review.Filing", f.id(), f.client(), f.revision()),
            List.of(Leaf.nested(Filing.DETAILS, FilingDetails.REFERENCE), Leaf.nested(Filing.DETAILS, FilingDetails.WORKING_NOTE)));

    @Test void practiceClientCannotReadAnotherClientWhileStaffHasDifferentNestedFields() {
        var client = reader.read(new Scope("t1", "client-a", "client", "practice-a"), FILING, ScopedReadTest::filings);
        assertThat(client.stream().map(View::fields).toList()).containsExactly(Map.of("review.Filing.details.reference", "REF-A"));
        var staff = reader.read(new Scope("t1", "employee-a", "staff", "practice-a"), FILING, ScopedReadTest::filings);
        assertThat(staff.stream().map(View::fields).toList()).containsExactly(
                Map.of("review.Filing.details.reference", "REF-A", "review.Filing.details.workingNote", "staff-working-note-A"),
                Map.of("review.Filing.details.reference", "REF-B", "review.Filing.details.workingNote", "staff-working-note-B"));
    }

    @Test void expiredOrCrossScopeAuthorityCannotReleaseEvenOtherwiseAllowedData() {
        var now = Instant.now();
        for (Authority invalid : List.of(
                new Authority(MEMBER, new EffectiveRead(true), now.minusSeconds(60), now.minusSeconds(1), "old"),
                new Authority(new Scope("t1", "member-a", "member", "network-b"), new EffectiveRead(true), now, now.plusSeconds(30), "other"))) {
            var guarded = new PostgresCedarRead(ScopedReadTest::login, (scope, at) -> invalid);
            assertThatThrownBy(() -> guarded.read(MEMBER, CONTACT, ScopedReadTest::contacts))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Authorised read unavailable").hasNoCause();
        }
    }

    static List<Filing> filings(Connection connection) throws Exception {
        var rows = new ArrayList<Filing>();
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT * FROM filing ORDER BY id LIMIT 33")) {
            while (rs.next()) rows.add(new Filing(rs.getString("tenant"), rs.getString("practice"), rs.getString("id"),
                    rs.getString("client"), rs.getLong("revision"), new FilingDetails(rs.getString("reference"), rs.getString("working_note"))));
        }
        return rows;
    }

    static List<Contact> contacts(Connection connection) throws Exception {
        var rows = new ArrayList<Contact>();
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT * FROM contact ORDER BY id LIMIT 33")) {
            while (rs.next()) rows.add(new Contact(rs.getString("tenant"), rs.getString("network"), rs.getString("id"),
                    rs.getString("person"), rs.getLong("revision"), new ContactDetails(rs.getString("display_name"),
                    rs.getString("email"), rs.getString("private_note"))));
        }
        return rows;
    }
    static Connection login() {
        try { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "review_login", "review"); }
        catch (Exception e) { throw new IllegalStateException("Database unavailable"); }
    }
}
