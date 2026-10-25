permit(principal == Staff::"employee", action, resource) when {
    context.capabilities has ManageFiling &&
    context.capabilities.ManageFiling has remaining &&
    context.capabilities.ManageFiling.remaining > 0 &&
    resource.clientReference == "client-one"
};
