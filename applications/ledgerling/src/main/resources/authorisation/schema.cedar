entity Staff;
entity Client;
entity FilingId = {
    clientReference: String,
    filingDueAt: String,
    FilingDueSoon?: { filingDueAt: String },
    RecordsOutstanding?: { requestId: String }
};

action "typed/v1/io.github.gmcnicol.ledgerling.FilingProjection.clientReference" appliesTo {
    principal: [Staff, Client],
    resource: [FilingId]
};
action "typed/v1/io.github.gmcnicol.ledgerling.FilingProjection.filingDueAt" appliesTo {
    principal: [Staff, Client],
    resource: [FilingId]
};
action "typed/v1/io.github.gmcnicol.ledgerling.FilingDueSoon" appliesTo {
    principal: [Staff, Client],
    resource: [FilingId]
};
action "typed/v1/io.github.gmcnicol.ledgerling.RecordsOutstanding" appliesTo {
    principal: [Staff, Client],
    resource: [FilingId]
};
action "typed/v1/io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived" appliesTo {
    principal: [Staff, Client],
    resource: [FilingId]
};
action "typed/v1/io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation" appliesTo {
    principal: [Staff, Client],
    resource: [FilingId]
};
