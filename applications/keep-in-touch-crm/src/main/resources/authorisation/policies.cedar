permit(principal is Owner, action, resource);

permit(
    principal is Viewer,
    action == Action::"io.github.gmcnicol.crm.Contact.displayName",
    resource
);

permit(
    principal is Viewer,
    action == Action::"io.github.gmcnicol.crm.FollowUpDue",
    resource
);
