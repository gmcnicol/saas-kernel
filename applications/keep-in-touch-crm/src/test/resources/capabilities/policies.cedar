permit(principal == Owner::"member", action, resource) when {
    context.capabilities has EngageContact &&
    context.capabilities.EngageContact has remaining &&
    context.capabilities.EngageContact.remaining > 0 &&
    resource.contactId == "member" &&
    resource.nextContactDueAt != "2099-01-01T00:00:00Z"
};

// Valid schema, but remaining = 3 overflows at runtime. Cedar skips the errored
// forbid; capability-protected Kernel decisions must not accept that Allow.
forbid(principal, action, resource) when {
    context.capabilities has EngageContact &&
    context.capabilities.EngageContact has remaining &&
    context.capabilities.EngageContact.remaining * 4611686018427387903 < 0
};
