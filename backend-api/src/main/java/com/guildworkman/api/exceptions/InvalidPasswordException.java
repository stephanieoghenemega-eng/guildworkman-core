package com.guildworkman.api.exceptions;

public class InvalidPasswordException extends RuntimeException {
    public InvalidPasswordException(String e) {
        super(e);
    }
}
