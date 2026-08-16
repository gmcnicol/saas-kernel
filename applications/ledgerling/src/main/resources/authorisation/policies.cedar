permit(principal is Staff, action, resource);

permit(
    principal is Client,
    action == Action::"typed/v1/io.github.gmcnicol.ledgerling.FilingProjection.clientReference",
    resource
);

permit(
    principal is Client,
    action == Action::"typed/v1/io.github.gmcnicol.ledgerling.FilingDueSoon",
    resource
);
