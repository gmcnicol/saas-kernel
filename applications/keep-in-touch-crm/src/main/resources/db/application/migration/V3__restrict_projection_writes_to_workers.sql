REVOKE INSERT, UPDATE, DELETE ON crm_contact_engagement_projection FROM kernel_runtime;
GRANT SELECT, INSERT, UPDATE ON crm_contact_engagement_projection TO kernel_worker;
