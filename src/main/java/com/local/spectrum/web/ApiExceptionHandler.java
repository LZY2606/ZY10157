package com.local.spectrum.web;

import com.local.spectrum.service.ConflictException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
        "error", "bad_request",
        "message", exception.getMessage(),
        "at", Instant.now().toString()));
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<Map<String, Object>> conflict(ConflictException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "error", "version_conflict",
        "message", exception.getMessage(),
        "at", Instant.now().toString()));
  }
}
