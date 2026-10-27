/**
 * Public, product-neutral contracts for Application-owned state and Kernel workflow mechanics.
 *
 * <p>{@link io.github.gmcnicol.kernel.application.Kernel#execute} composes one exact Intent with an
 * Application transaction. That transaction must participate in the Kernel datasource; a completed result is
 * provisional until its outer commit. Queue processing remains available separately through
 * {@link io.github.gmcnicol.kernel.application.Kernel#processNext} and
 * {@link io.github.gmcnicol.kernel.application.Kernel#processDue}.
 */
@org.springframework.modulith.NamedInterface("application")
package io.github.gmcnicol.kernel.application;
