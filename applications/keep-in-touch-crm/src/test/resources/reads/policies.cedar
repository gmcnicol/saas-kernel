permit(principal == Owner::"member", action in [
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.read",
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.details",
    Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.details.displayName"
], resource) when {
    context.capabilities has EngageContact &&
    resource.network == context.readScope && resource.owner == "member"
};

// Valid policy can overflow at runtime. Any evaluation error fails the entire read.
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

// Fail only a later candidate, after an earlier record (and displayName for the
// field-error case) has been buffered. privateNote normally has an ordinary Deny.
forbid(principal, action == Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.read", resource) when {
    resource.contactId == "record-error" &&
    context.capabilities has EngageContact && context.capabilities.EngageContact has remaining &&
    context.capabilities.EngageContact.remaining * 9223372036854775807 < 0
};
forbid(principal, action == Action::"typed/v1/io.github.gmcnicol.crm.NetworkContactRead.details.privateNote", resource) when {
    resource.contactId == "field-error" &&
    context.capabilities has EngageContact && context.capabilities.EngageContact has remaining &&
    context.capabilities.EngageContact.remaining * 9223372036854775807 < 0
};
