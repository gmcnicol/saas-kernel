entity User;
entity Contact;

action "recordInteraction" appliesTo {
    principal: [User],
    resource: [Contact]
};
