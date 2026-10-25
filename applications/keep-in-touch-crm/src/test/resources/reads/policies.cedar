permit(principal == Owner::"member", action in [
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.read",
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.details",
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.details.displayName"
], resource) when {
    context.capabilities has EngageContact &&
    resource.network == context.readScope && resource.owner == "member"
};

// As in the #68 harness, valid policy can overflow at runtime. Error-free Allow is required.
forbid(principal, action == Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.read", resource) when {
    context.capabilities has EngageContact && context.capabilities.EngageContact has remaining &&
    context.capabilities.EngageContact.remaining == 3 &&
    context.capabilities.EngageContact.remaining * 4611686018427387903 < 0
};
forbid(principal, action == Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.details.displayName", resource) when {
    context.capabilities has EngageContact && context.capabilities.EngageContact has remaining &&
    context.capabilities.EngageContact.remaining == 4 &&
    context.capabilities.EngageContact.remaining * 4611686018427387903 < 0
};
