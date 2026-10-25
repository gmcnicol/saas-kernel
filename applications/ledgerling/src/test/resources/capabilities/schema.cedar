entity Staff;
entity Client;
entity FilingId = { clientReference: String, RecordsOutstanding?: { requestId: String } };
type Authority = { capabilities: { ManageFiling?: { remaining?: Long }, ExportFilings?: {} } };
action "typed/v1/io.github.gmcnicol.ledgerling.RecordsOutstanding" appliesTo {
    principal: [Staff, Client], resource: [FilingId], context: Authority
};
action "typed/v1/io.github.gmcnicol.ledgerling.FilingDueSoon" appliesTo {
    principal: [Staff, Client], resource: [FilingId], context: Authority
};
action "typed/v1/io.github.gmcnicol.ledgerling.FilingProjection.clientReference" appliesTo {
    principal: [Staff, Client], resource: [FilingId], context: Authority
};
action "typed/v1/io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived" appliesTo {
    principal: [Staff, Client], resource: [FilingId], context: Authority
};
action "typed/v1/io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation" appliesTo {
    principal: [Staff, Client], resource: [FilingId], context: Authority
};
