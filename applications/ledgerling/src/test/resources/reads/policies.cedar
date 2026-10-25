permit(principal == Staff::"employee", action in [
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.read",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details.reference",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details.workingNotes"
], resource) when {
    context.capabilities has ManageFiling && resource.practice == context.readScope
};
permit(principal == Client::"client-one", action in [
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.read",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details",
    Action::"typed/v1/io.github.gmcnicol.ledgerling.ClientFilingRead.details.reference"
], resource) when {
    context.capabilities has ManageFiling && resource.practice == context.readScope && resource.assignedClient == "client-one"
};
