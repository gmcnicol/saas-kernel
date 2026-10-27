entity Member;
entity EntryId = { directory: String, owner: String };
entity DirectoryId;
type Authority = { capabilities: { Access?: { actor: String }, PracticeAccess?: { actor: String } }, readScope?: String };
action "typed/v1/example.directory.Entry.read",
       "typed/v1/example.directory.Entry.details.name",
       "typed/v1/example.directory.Entry.details.privateNote",
       "typed/v1/example.directory.Entry.reviewed",
       "typed/v1/example.directory.DirectoryActions.review" appliesTo {
  principal: [Member], resource: [EntryId], context: Authority
};
entity Staff;
entity Client;
entity PracticeId;
entity FileId = { practice: String, assignedTo: String };
action "typed/v1/example.practice.ClientFile.read",
       "typed/v1/example.practice.ClientFile.reference",
       "typed/v1/example.practice.ClientFile.notes" appliesTo {
  principal: [Staff, Client], resource: [FileId], context: Authority
};
action "typed/v1/example.practice.ClientFile.reference.query" appliesTo {
  principal: [Staff, Client], resource: [PracticeId], context: Authority
};
