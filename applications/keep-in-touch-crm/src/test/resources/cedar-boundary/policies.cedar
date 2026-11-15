permit(principal is Owner, action, resource);

// A well-typed extension call can still fail on a decision-local value.
forbid(principal is Owner, action, resource) when {
    ip(resource.contactId).isIpv4()
};
