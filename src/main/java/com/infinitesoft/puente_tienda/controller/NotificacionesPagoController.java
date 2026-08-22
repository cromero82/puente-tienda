package com.infinitesoft.puente_tienda.controller;

import com.infinitesoft.puente_tienda.dto.AsignarConfirmacionRequest;
import com.infinitesoft.puente_tienda.dto.MarcarConfirmadasRequest;
import com.infinitesoft.puente_tienda.dto.NotificacionSinAsignarDto;
import com.infinitesoft.puente_tienda.dto.PendienteConfirmacionDto;
import com.infinitesoft.puente_tienda.entities.TicketSinNotificacion;
import com.infinitesoft.puente_tienda.service.ConfirmacionPagoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class NotificacionesPagoController {

    private final ConfirmacionPagoService service;

    @GetMapping("/api/notificaciones/pendientes")
    public List<PendienteConfirmacionDto> pendientes(@RequestParam Long sesionId) {
        log.debug("GET pendientes sesionId={}", sesionId);
        return service.listarPendientes(sesionId);
    }

    @GetMapping("/api/notificaciones/sin-asignar")
    public List<NotificacionSinAsignarDto> sinAsignar() {
        log.debug("GET notificaciones sin-asignar");
        return service.listarSinAsignar();
    }

    @PutMapping("/api/notificaciones/confirmadas")
    public ResponseEntity<?> marcarConfirmadas(@RequestBody MarcarConfirmadasRequest req) {
        log.info("PUT confirmadas/vista ids={}",
                req.getHistorialElectronicoIds() != null ? req.getHistorialElectronicoIds().size() : 0);
        service.marcarConfirmadas(req.getHistorialElectronicoIds());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/api/recibos-electronicos/{id}/asignar")
    public PendienteConfirmacionDto asignar(
            @PathVariable Long id,
            @RequestBody AsignarConfirmacionRequest req) {
        boolean confirmarDistinto = Boolean.TRUE.equals(req.getConfirmarMontoDistinto());
        log.info("PUT asignar historialElectronicoId={} notificacionId={} confirmarMontoDistinto={} ofDevolucion={}",
                id, req.getNotificacionId(), confirmarDistinto, req.getOrigenFondosDevolucionId());
        return service.asignar(id, req.getNotificacionId(), confirmarDistinto, req.getOrigenFondosDevolucionId());
    }

    @PutMapping("/api/recibos-electronicos/{id}/ya-no-esperar")
    public PendienteConfirmacionDto yaNoEsperar(@PathVariable Long id) {
        log.info("PUT ya-no-esperar historialElectronicoId={}", id);
        return service.marcarYaNoEsperar(id);
    }

    @GetMapping("/api/tickets-sin-notificacion")
    public List<TicketSinNotificacion> ticketsSinNotificacion() {
        log.info("GET tickets-sin-notificacion");
        return service.listarTicketsSinNotificacion();
    }
}
