package io.github.gmcnicol.kernel.application;

public class AuthorisationDeniedException extends RuntimeException {

    public AuthorisationDeniedException() {
        super("Authorisation denied");
    }
}
