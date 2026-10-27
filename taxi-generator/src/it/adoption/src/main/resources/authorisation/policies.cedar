// Effective Access is necessary, never sufficient: keep record/field rules independent.
permit(principal is Member,
  action in [Action::"typed/v1/example.directory.Entry.read", Action::"typed/v1/example.directory.Entry.details.name", Action::"typed/v1/example.directory.Entry.reviewed"],
  resource is EntryId)
when { context.capabilities has Access && context has readScope && resource.directory == context.readScope && resource.owner == context.capabilities.Access.actor };
permit(principal is Member, action == Action::"typed/v1/example.directory.DirectoryActions.review", resource is EntryId)
when { context.capabilities has Access && resource.owner == context.capabilities.Access.actor };
// No privateNote permit; a parent/record permit cannot reveal it.

permit(principal, action in [Action::"typed/v1/example.practice.ClientFile.read", Action::"typed/v1/example.practice.ClientFile.reference"], resource is FileId)
when { context.capabilities has PracticeAccess && context has readScope && resource.practice == context.readScope &&
       (principal is Staff || (principal is Client && resource.assignedTo == context.capabilities.PracticeAccess.actor)) };
permit(principal is Staff, action == Action::"typed/v1/example.practice.ClientFile.notes", resource is FileId)
when { context.capabilities has PracticeAccess && context has readScope && resource.practice == context.readScope };
permit(principal, action == Action::"typed/v1/example.practice.ClientFile.reference.query", resource is PracticeId)
when { context.capabilities has PracticeAccess && (principal is Staff || principal is Client) };
