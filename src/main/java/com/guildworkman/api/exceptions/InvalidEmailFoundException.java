package com.guildworkman.api.exceptions;

public class InvalidEmailFoundException extends RuntimeException {
    public InvalidEmailFoundException(String e) {
        super(e);
    }
}
