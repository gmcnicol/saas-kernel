package io.github.gmcnicol.kernel.semanticpack;

/** Application-owned Cedar schema and policies loaded from a fixed classpath manifest. */
public interface AuthorisationBundle {

    String manifestResource();
}
