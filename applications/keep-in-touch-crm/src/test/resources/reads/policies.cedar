permit(principal == Owner::"member", action in [
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.read",
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.details",
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.details.displayName"
], resource) when {
    context.capabilities has EngageContact &&
    resource.network == context.readScope && resource.owner == "member"
};
