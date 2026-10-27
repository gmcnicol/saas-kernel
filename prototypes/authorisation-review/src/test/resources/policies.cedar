// The parent grant is deliberately broad: it must NEVER release raw children.
permit(principal, action == Action::"read", resource)
when { principal.enabled && principal.tenant == resource.tenant && principal.scope == resource.scope
       && principal.kind == "member" && resource.model == "review.Contact" };
permit(principal, action in [Action::"review.Contact.details", Action::"review.Contact.details.displayName"], resource)
when { principal.kind == "member" };

permit(principal, action == Action::"read", resource)
when { principal.enabled && principal.tenant == resource.tenant && principal.scope == resource.scope
       && resource.model == "review.Filing"
       && (principal.kind == "staff" || (principal.kind == "client" && principal.actor == resource.owner)) };
permit(principal, action == Action::"review.Filing.details.reference", resource)
when { principal.kind == "staff" || principal.kind == "client" };
permit(principal, action == Action::"review.Filing.details.workingNote", resource)
when { principal.kind == "staff" };
