package io.github.gmcnicol.kernel.application;

import java.util.UUID;

/** Opaque bearer reference to an Action authorised for one principal. */
public record ActionOffer(UUID id, String actionId) {}
