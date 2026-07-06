package com.guildworkman.api.exceptions;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String e) {
        super(e);
    }
}
