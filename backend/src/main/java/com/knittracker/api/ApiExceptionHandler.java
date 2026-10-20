package com.knittracker.api;

import com.knittracker.service.ConflictException;
import com.knittracker.service.NotFoundException;
import com.knittracker.service.PatternImportService.InvalidIrException;
import com.knittracker.service.auth.AuthService.AuthFailedException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> notFound(NotFoundException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(AuthFailedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> authFailed(AuthFailedException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(ConflictException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(InvalidIrException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, List<String>> invalidIr(InvalidIrException e) {
        return Map.of("errors", e.errors());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badRequest(Exception e) {
        return Map.of("error", e.getMessage());
    }
}
