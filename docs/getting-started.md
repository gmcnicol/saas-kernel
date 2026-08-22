# Getting started

Use Java 25 for the Application and Java 21 or newer to run Maven. Add both same-version artefacts:

```xml
<dependency>
  <groupId>io.github.gmcnicol</groupId>
  <artifactId>saas-kernel</artifactId>
  <version>${saas-kernel.version}</version>
</dependency>
<plugin>
  <groupId>io.github.gmcnicol</groupId>
  <artifactId>saas-kernel-taxi-generator</artifactId>
  <version>${saas-kernel.version}</version>
  <configuration>
    <sourceDirectory>${project.basedir}/src/main/resources/semantic-pack</sourceDirectory>
    <basePackage>com.example.bindings</basePackage>
  </configuration>
  <executions><execution><goals><goal>generate</goal></goals></execution></executions>
</plugin>
```

Author Taxi as the source of semantic meaning:

```taxi
import io.github.gmcnicol.kernel.taxi.Subject
import io.github.gmcnicol.kernel.taxi.Contract
import io.github.gmcnicol.kernel.taxi.ProjectedState
import io.github.gmcnicol.kernel.taxi.Fact
import io.github.gmcnicol.kernel.taxi.Event
import io.github.gmcnicol.kernel.taxi.ActionService

namespace example.followup

@Subject type ContactId inherits String
@Contract(version = 1)
@ProjectedState(subject = "example.followup.ContactId")
model FollowUp { contactId: ContactId, dueAt: Instant, complete: Boolean }
@Contract(version = 1)
@Fact(projection = "example.followup.FollowUp")
model FollowUpDue { contactId: ContactId }
@Contract(version = 1) model RecordInteraction { note: String }
@Contract(version = 1) @Event model InteractionRecorded { contactId: ContactId }
@ActionService(projection = "example.followup.FollowUp")
service FollowUpActions {
  operation record(input: RecordInteraction): InteractionRecorded[]
}
```

Run `./mvnw generate-sources`. Import the generated source directory in the IDE if Maven integration does not do so automatically. Never edit or commit generated Java.

Application configuration binds generated slots:

```java
@Bean
TypedFactDerivation<FollowUp, FollowUpDue> due() {
    return FollowUpDue.DERIVATION.bind((state, now) -> state.complete()
            ? TypedFactDerivation.Result.none()
            : TypedFactDerivation.Result.fact(new FollowUpDue(state.contactId())));
}

@Bean
TypedApplicabilityPolicy<FollowUp> record() {
    return FollowUpActions.RECORD.bindApplicability((state, facts) -> !state.complete());
}

@Bean
TypedIntentHandler<FollowUp, RecordInteraction, InteractionRecorded> handler() {
    return FollowUpActions.RECORD.bindHandler((intent, input, state) -> List.of(
            new TypedStateTransition<>(
                    new InteractionRecorded(state.contactId()),
                    new FollowUp(state.contactId(), state.dueAt(), true))));
}

@Bean
TypedEventProjector<FollowUp, InteractionRecorded> projector(JdbcTemplate jdbc) {
    return FollowUpActions.RECORD.bindProjector(InteractionRecorded.TYPE, transition ->
            jdbc.update("""
                    update contact_follow_up set complete = true
                    where tenant_id = ? and contact_id = ?
                    """, transition.tenantId(), transition.event().contactId().value()));
}
```

Map the same generated identities in Cedar:

```cedar
entity Owner;
entity ContactId = { contactId: String, FollowUpDue?: { contactId: String } };
action "typed/v1/example.followup.FollowUpActions.record" appliesTo {
  principal: [Owner], resource: [ContactId]
};
permit(principal is Owner, action, resource);
```

Presentation consumes only the authorised typed envelope and issued offers:

```java
TypedPresentationPack<ContactId, FollowUp> followUpPage() {
    return envelope -> new PresentationResult(
            "<h1>" + HtmlUtils.htmlEscape(envelope.subject().externalId()) + "</h1>"
                    + envelope.actionOffers().stream()
                            .map(offer -> "<button data-offer='" + offer.id() + "'>Record</button>")
                            .collect(Collectors.joining()),
            "", envelope.actionOffers().stream().map(TypedActionOffer::id).collect(Collectors.toSet()));
}
```

Read the Application row directly into `new FollowUp(new ContactId(id), dueAt, complete)`, then call `Kernel.evaluate(TypedProjectedState, Instant)`. Derivations produce typed Facts. `Kernel.authorise` applies Cedar to fields, Facts and Actions. A client accepts only an issued Action Offer with `FollowUpActions.RECORD.candidate(new RecordInteraction(note))`. The Intent Worker invokes the bound handler, persists the ordered Event, and calls the projector in the same completion transaction. No step converts semantic state through a map or JSON round trip.

Application-owned relational tables remain the normal read and write model. See [Persistence](persistence.md), [Generator reference](taxi-generator.md), and the CRM Acceptance Fixture for a complete executable path.
