entity Owner;
entity Viewer;
entity Contact;

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
