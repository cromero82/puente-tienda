package com.infinitesoft.puente_tienda.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.persistence.EntityNotFoundException;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class RestExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, EntityNotFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(RuntimeException ex, HttpServletRequest req) {
        log.warn("{} {} negocio: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        boolean missing = ex.getMessage() != null && ex.getMessage().toLowerCase().contains("no encontrada");
        return ResponseEntity
                .status(missing ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST)
                .body(body(false, ex.getMessage(), ex));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        log.warn("{} {} param inválido name={} value={}",
                req.getMethod(), req.getRequestURI(), ex.getName(), ex.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(false, "Parámetro inválido: " + ex.getName(), ex));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest req) {
        String detail = rootMessage(ex);
        log.error("{} {} integridad DB: {}", req.getMethod(), req.getRequestURI(), detail, ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(false, "No se pudo completar por restricción de datos", ex));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException ex, HttpServletRequest req) {
        String detail = rootMessage(ex);
        log.error("{} {} error DB: {}", req.getMethod(), req.getRequestURI(), detail, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(false, "Error de base de datos", ex));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex, HttpServletRequest req) {
        log.error("{} {} error no controlado: {}", req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(false, "Error interno", ex));
    }

    private static Map<String, Object> body(boolean ok, String error, Exception ex) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", ok);
        map.put("error", error != null ? error : "Error");
        map.put("tipo", ex.getClass().getSimpleName());
        String detail = rootMessage(ex);
        if (detail != null && !detail.equals(error)) {
            map.put("detalle", detail);
        }
        return map;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
