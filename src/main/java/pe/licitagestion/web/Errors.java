package pe.licitagestion.web;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class Errors {
    private static final Logger log = LoggerFactory.getLogger(Errors.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst()
            .orElse("Datos inválidos");
        return response(422, detail);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception e) {
        return response(422, e instanceof IllegalArgumentException ? e.getMessage() : "Formato de solicitud inválido");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied() {
        return response(403, "No tienes permiso para esta operación");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) {
        return response(e.getStatusCode().value(), e.getReason());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> duplicate() {
        return response(409, "El registro ya existe; revisa el código, correo o tarea vinculada");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(DataAccessException e) {
        return response(503, "No se pudo completar la operación en la base de datos");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> large() {
        return response(413, "El archivo supera los 10 MB permitidos");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> generic(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root != root.getCause()) {
            root = root.getCause();
            if (root instanceof AccessDeniedException) return denied();
            if (root instanceof ResponseStatusException rse) return status(rse);
            if (root instanceof IllegalArgumentException) return invalid((Exception) root);
        }
        log.error("Unexpected application error: ", e);
        return response(500, "Ocurrió un error inesperado; no se pudo completar la operación");
    }

    private ResponseEntity<?> response(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message == null ? "Solicitud inválida" : message));
    }
}

