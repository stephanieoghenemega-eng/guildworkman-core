package com.skilledservice.ClientService.handler;

import com.skilledservice.ClientService.exceptions.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<?>handleUserNotFound(UserNotFoundException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "success", false));
    }
    @ExceptionHandler(AppointmentNotFoundException.class)
    public ResponseEntity<?> handleAppointmentNotFound(AppointmentNotFoundException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "success", false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGlobalException(Exception exception){
        return ResponseEntity.status(BAD_REQUEST)
               .body(Map.of(
                        "error", exception.getMessage(),
                        "success", false));
    }
    @ExceptionHandler(GuildWorkmanException.class)
    public ResponseEntity<?> handleGuildWorkmanException(GuildWorkmanException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "success", false));

    }
    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<?>handleInvalidPasswordException(@NotNull InvalidPasswordException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of("error", exception.getMessage(),
                        "success", false));
    }

    @ExceptionHandler(InvalidEmailFoundException.class)
    public ResponseEntity<?>handleInvalidEmailFoundException(InvalidEmailFoundException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of("error", exception.getMessage(),
                        "success", false));
    }
}
