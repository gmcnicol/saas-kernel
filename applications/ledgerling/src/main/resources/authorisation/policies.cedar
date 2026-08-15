permit(principal is Staff, action, resource);

permit(
    principal is Client,
    action == Action::"io.github.gmcnicol.ledgerling.Filing.status",
    resource
);

permit(
    principal is Client,
    action == Action::"io.github.gmcnicol.ledgerling.FilingDueSoon",
    resource
);
