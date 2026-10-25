entity Owner;
entity Viewer;
entity ContactId = { contactId: String, FollowUpDue?: { contactId: String } };
type Authority = { capabilities: { EngageContact?: { remaining?: Long }, ExportContacts?: {} } };
action "typed/v1/io.github.gmcnicol.crm.FollowUpDue" appliesTo {
    principal: [Owner, Viewer], resource: [ContactId], context: Authority
};
action "typed/v1/io.github.gmcnicol.crm.TypedCrmActions.recordInteraction" appliesTo {
    principal: [Owner, Viewer], resource: [ContactId], context: Authority
};
action "typed/v1/io.github.gmcnicol.crm.FollowUpProjection.contactId" appliesTo {
    principal: [Owner, Viewer], resource: [ContactId], context: Authority
};
