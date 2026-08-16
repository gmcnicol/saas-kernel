permit(principal is Owner, action, resource);

permit(
    principal is Viewer,
    action == Action::"typed/v1/io.github.gmcnicol.crm.FollowUpProjection.contactId",
    resource
) when {
    resource has FollowUpDue && resource.FollowUpDue.contactId == resource.contactId
};

permit(
    principal is Viewer,
    action == Action::"typed/v1/io.github.gmcnicol.crm.FollowUpDue",
    resource
) when {
    resource has FollowUpDue && resource.FollowUpDue.contactId == resource.contactId
};
