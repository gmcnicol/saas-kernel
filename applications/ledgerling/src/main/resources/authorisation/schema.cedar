entity Staff;
entity Client;
entity Filing;

action "io.github.gmcnicol.ledgerling.Filing.status" appliesTo {
    principal: [Staff, Client],
    resource: [Filing]
};
action "io.github.gmcnicol.ledgerling.Filing.staffNote" appliesTo {
    principal: [Staff, Client],
    resource: [Filing]
};
action "io.github.gmcnicol.ledgerling.FilingDueSoon" appliesTo {
    principal: [Staff, Client],
    resource: [Filing]
};
action "io.github.gmcnicol.ledgerling.RecordsOutstanding" appliesTo {
    principal: [Staff, Client],
    resource: [Filing]
};
action "io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived" appliesTo {
    principal: [Staff, Client],
    resource: [Filing]
};
action "io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation" appliesTo {
    principal: [Staff, Client],
    resource: [Filing]
};
