package io.github.gmcnicol.kernel.authorisation;

/** Application-owned Cedar schema and policies loaded from a fixed classpath manifest. */
public interface AuthorisationBundle {

    String manifestResource();
}
