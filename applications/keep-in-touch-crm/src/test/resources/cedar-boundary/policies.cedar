permit(principal is Owner, action, resource);

// Well-typed arithmetic can still overflow on the evaluated branch.
forbid(principal is Owner, action, resource) when {
    resource.contactId != "allowed" &&
    (resource.contactId == "blocked" || 9223372036854775807 + 1 == 0)
};
