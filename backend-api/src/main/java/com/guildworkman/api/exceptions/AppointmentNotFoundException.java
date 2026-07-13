package com.guildworkman.api.exceptions;

public class AppointmentNotFoundException extends RuntimeException {
    public AppointmentNotFoundException(String e) {
        super(e);
    }
}
