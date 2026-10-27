permit(principal == Staff::"employee", action, resource) when {
    context.capabilities has ManageFiling &&
    context.capabilities.ManageFiling has remaining &&
    context.capabilities.ManageFiling.remaining > 0 &&
    resource.clientReference == "client-one"
};

// Valid schema, but remaining = 3 overflows at runtime. Cedar skips the errored
// forbid; capability-protected Kernel decisions must not accept that Allow.
forbid(principal, action, resource) when {
    context.capabilities has ManageFiling &&
    context.capabilities.ManageFiling has remaining &&
    context.capabilities.ManageFiling.remaining * 4611686018427387903 < 0
};
