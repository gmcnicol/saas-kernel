entity Owner;
entity Viewer;
entity ContactId = {
    contactId: String,
    FollowUpDue?: { contactId: String }
};
action "typed/v1/io.github.gmcnicol.crm.FollowUpDue" appliesTo {
    principal: [Owner, Viewer],
    resource: [ContactId]
};
action "typed/v1/io.github.gmcnicol.crm.TypedCrmActions.recordInteraction" appliesTo {
    principal: [Owner, Viewer],
    resource: [ContactId]
};
action "typed/v1/io.github.gmcnicol.crm.FollowUpProjection.contactId" appliesTo {
    principal: [Owner, Viewer],
    resource: [ContactId]
};
