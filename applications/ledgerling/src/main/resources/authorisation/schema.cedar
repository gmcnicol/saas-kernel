entity Staff;
entity DocumentRequest;

action "recordRecordsReceived" appliesTo {
    principal: [Staff],
    resource: [DocumentRequest]
};
