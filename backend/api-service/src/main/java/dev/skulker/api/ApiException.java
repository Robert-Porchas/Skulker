package dev.skulker.api;

import org.springframework.http.HttpStatus;

final class ApiException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final HttpStatus status;

  ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  HttpStatus status() {
    return status;
  }
}
