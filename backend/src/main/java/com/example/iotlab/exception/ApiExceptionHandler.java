package com.example.iotlab.exception;

import com.example.iotlab.api.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

/** Errores de la API como ProblemDetail (RFC 9457). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Parámetros que no se pudieron convertir (p. ej. from=ayer). */
    @ExceptionHandler(ServerWebInputException.class)
    ProblemDetail badInput(ServerWebInputException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getReason());
    }
}
