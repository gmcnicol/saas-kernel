entity Owner;
entity Viewer;
entity Contact;
entity ContactId = {
    contactId: String,
    FollowUpDue?: { contactId: String }
};

action "io.github.gmcnicol.crm.Contact.displayName" appliesTo {
    principal: [Owner, Viewer],
    resource: [Contact]
};
action "io.github.gmcnicol.crm.FollowUpDue" appliesTo {
    principal: [Owner, Viewer],
    resource: [Contact]
};
action "io.github.gmcnicol.crm.CrmActions.recordInteraction" appliesTo {
    principal: [Owner, Viewer],
    resource: [Contact]
};
action "io.github.gmcnicol.crm.CrmActions.snoozeFollowUp" appliesTo {
    principal: [Owner, Viewer],
    resource: [Contact]
};
action "io.github.gmcnicol.crm.CrmActions.completeFollowUp" appliesTo {
    principal: [Owner, Viewer],
    resource: [Contact]
};
action "io.github.gmcnicol.crm.TypedCrmActions.recordInteraction" appliesTo {
    principal: [Owner, Viewer],
    resource: [Contact]
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
