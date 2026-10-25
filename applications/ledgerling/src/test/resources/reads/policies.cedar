// Explicit negative permissions must override matching permits (synthetic contract cases).
forbid(principal, action == Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.read", resource) when {
    context.capabilities has ManageFiling && context.capabilities.ManageFiling has remaining && context.capabilities.ManageFiling.remaining == 0
};
forbid(principal, action == Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details.reference", resource) when {
    context.capabilities has ManageFiling && context.capabilities.ManageFiling has remaining && context.capabilities.ManageFiling.remaining == 1
};
permit(principal, action == Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.filingId.query", resource)
when { (principal == Staff::"employee" || principal == Client::"client-one") && context.capabilities has ManageFiling && context.readScope == "practice-one" };
permit(principal == Staff::"employee", action in [
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.filingId",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.read",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details.reference",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details.workingNotes"
], resource) when {
    context.capabilities has ManageFiling && resource.practice == context.readScope
};
permit(principal == Client::"client-one", action in [
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.filingId",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.read",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details.reference"
], resource) when {
    context.capabilities has ManageFiling && resource.practice == context.readScope && resource.assignedClient == "client-one"
};
