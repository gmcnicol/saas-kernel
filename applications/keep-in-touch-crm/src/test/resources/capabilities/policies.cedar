permit(principal == Owner::"member", action, resource) when {
    context.capabilities has EngageContact &&
    context.capabilities.EngageContact has remaining &&
    context.capabilities.EngageContact.remaining > 0 &&
    resource.contactId == "member"
};
