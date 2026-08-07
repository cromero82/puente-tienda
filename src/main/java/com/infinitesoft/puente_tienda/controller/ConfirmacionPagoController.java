package com.infinitesoft.puente_tienda.controller;

import com.infinitesoft.puente_tienda.dto.AsignarConfirmacionRequest;
import com.infinitesoft.puente_tienda.dto.PendienteConfirmacionDto;
import com.infinitesoft.puente_tienda.service.ConfirmacionPagoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/confirmaciones-pago")
@RequiredArgsConstructor
public class ConfirmacionPagoController {

    private final ConfirmacionPagoService service;

    @GetMapping("/pendientes")
    public List<PendienteConfirmacionDto> pendientes(@RequestParam Long sesionId) {
        return service.listarParaSesion(sesionId);
    }

    @PutMapping("/{id}/vista")
    public ResponseEntity<?> marcarVista(@PathVariable Long id) {
        service.marcarVista(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/asignar")
    public PendienteConfirmacionDto asignar(@RequestBody AsignarConfirmacionRequest req) {
        return service.asignar(req);
    }
}
