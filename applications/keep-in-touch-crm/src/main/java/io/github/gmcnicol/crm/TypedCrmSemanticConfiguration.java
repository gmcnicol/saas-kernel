package io.github.gmcnicol.crm;

import io.github.gmcnicol.crm.bindings.GeneratedSemanticBindings;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpDue;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.InteractionRecordedEventV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import io.github.gmcnicol.kernel.application.TypedAuthorisationModel;
import io.github.gmcnicol.kernel.application.TypedStateTransition;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class TypedCrmSemanticConfiguration {

    @Bean
    SemanticBindings typedBindings() {
        return GeneratedSemanticBindings.INSTANCE;
    }

    @Bean
    TypedFactDerivation<FollowUpProjection, FollowUpDue> typedFollowUpDueDerivation() {
        return FollowUpDue.DERIVATION.bind((projection, evaluatedAt) ->
                projection.followUpCompleted()
                        ? TypedFactDerivation.Result.none()
                        : evaluatedAt.isBefore(projection.nextContactDueAt())
                                ? TypedFactDerivation.Result.later(projection.nextContactDueAt())
                                : TypedFactDerivation.Result.fact(new FollowUpDue(projection.contactId())));
    }

    @Bean
    TypedApplicabilityPolicy<FollowUpProjection> typedRecordInteractionApplicability() {
        return TypedCrmActions.RECORD_INTERACTION.bindApplicability(
                (projection, facts) -> facts.find(FollowUpDue.TYPE).isPresent());
    }

    @Bean
    TypedIntentHandler<FollowUpProjection, RecordInteractionCandidateV1, InteractionRecordedEventV1>
    typedRecordInteractionHandler() {
        return TypedCrmActions.RECORD_INTERACTION.bindHandler((intent, payload, projection) -> List.of(
                new TypedStateTransition<>(
                        new InteractionRecordedEventV1(projection.contactId()),
                        new FollowUpProjection(projection.contactId(), projection.nextContactDueAt(), true))));
    }

    @Bean
    TypedEventProjector<FollowUpProjection, InteractionRecordedEventV1> typedInteractionRecordedProjector(
            JdbcTemplate jdbc) {
        return TypedCrmActions.RECORD_INTERACTION.bindProjector(
                InteractionRecordedEventV1.TYPE, transition -> jdbc.update("""
                        UPDATE crm_contact_engagement_projection SET open_follow_up_id = NULL
                        WHERE tenant_id = ? AND contact_id = ?
                        """, transition.tenantId(), transition.event().contactId().value()));
    }
}
