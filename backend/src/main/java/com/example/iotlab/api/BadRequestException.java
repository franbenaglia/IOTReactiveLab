package com.example.iotlab.api;

/** Parámetro inválido en la API: se responde 400 (ver ApiExceptionHandler). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
