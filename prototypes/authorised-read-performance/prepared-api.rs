// THROWAWAY extension to pinned Cedar v4.2.2 ffi/is_authorized.rs.
// Uses that module's own FFI parsers and diagnostic conversion, not a new policy parser.
/// Immutable parsed bundle for the throwaway prepared evaluator.
#[derive(Debug)]
pub struct PrototypePrepared {
    schema: Option<crate::Schema>,
    policies: crate::PolicySet,
    warnings: Vec<DetailedError>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PrototypeBundle { schema: Option<Schema>, policies: PolicySet }
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PrototypeCall {
    principal: EntityUid, action: EntityUid, resource: EntityUid,
    context: Context, entities: Entities, validate_request: bool,
}
impl PrototypePrepared {
    /// Parse immutable schema/policy once, retaining upstream warnings.
    pub fn prepare(json: &str) -> Result<Self, String> {
        let b: PrototypeBundle = serde_json::from_str(json).map_err(|_| "bundle JSON")?;
        let (schema, warnings) = match b.schema {
            Some(s) => { let (s, w) = s.parse().map_err(|_| "schema")?; (Some(s), w.into_iter().map(miette::Report::new).map(Into::into).collect()) },
            None => (None, vec![]),
        };
        let policies = b.policies.parse().map_err(|_| "policies")?;
        Ok(Self { schema, policies, warnings })
    }
    /// Parse the exact decision-local request/entities, then authorize.
    pub fn evaluate(&self, value: serde_json::Value) -> Result<AuthorizationAnswer, String> {
        let c: PrototypeCall = serde_json::from_value(value).map_err(|_| "request JSON")?;
        let mut errors = vec![];
        let p = c.principal.parse(Some("principal")).map_err(|e| errors.push(e));
        let a = c.action.parse(Some("action")).map_err(|e| errors.push(e));
        let r = c.resource.parse(Some("resource")).map_err(|e| errors.push(e));
        let parsed = match (p, a, r) {
            (Ok(p), Ok(a), Ok(r)) => match c.context.parse(self.schema.as_ref(), Some(&a)) {
                Ok(ctx) => {
                    let request = Request::new(p, a, r, ctx, if c.validate_request { self.schema.as_ref() } else { None })
                        .map_err(|e| errors.push(e.into()));
                    let entities = c.entities.parse(self.schema.as_ref()).map_err(|e| errors.push(e));
                    match (request, entities) { (Ok(q), Ok(e)) => Some((q, e)), _ => None }
                },
                Err(e) => { errors.push(e); None },
            },
            _ => None,
        };
        Ok(match parsed {
            Some((q, e)) => AuthorizationAnswer::Success {
                response: AUTHORIZER.with(|a| a.is_authorized(&q, &self.policies, &e).into()),
                warnings: self.warnings.clone(),
            },
            None => AuthorizationAnswer::Failure { errors: errors.into_iter().map(Into::into).collect(), warnings: self.warnings.clone() },
        })
    }
}
