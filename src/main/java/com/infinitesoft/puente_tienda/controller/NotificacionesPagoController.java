package com.infinitesoft.puente_tienda.controller;

import com.infinitesoft.puente_tienda.dto.AsignarConfirmacionRequest;
import com.infinitesoft.puente_tienda.dto.MarcarConfirmadasRequest;
import com.infinitesoft.puente_tienda.dto.PendienteConfirmacionDto;
import com.infinitesoft.puente_tienda.service.ConfirmacionPagoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class NotificacionesPagoController {

    private final ConfirmacionPagoService service;

    @GetMapping("/api/notificaciones/pendientes")
    public List<PendienteConfirmacionDto> pendientes(@RequestParam Long sesionId) {
        return service.listarPendientes(sesionId);
    }

    @PutMapping("/api/notificaciones/confirmadas")
    public ResponseEntity<?> marcarConfirmadas(@RequestBody MarcarConfirmadasRequest req) {
        service.marcarConfirmadas(req.getHistorialElectronicoIds());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/api/recibos-electronicos/{id}/asignar")
    public PendienteConfirmacionDto asignar(
            @PathVariable Long id,
            @RequestBody AsignarConfirmacionRequest req) {
        Long notifId = req.getNotificacionId();
        return service.asignar(id, notifId);
    }
}
