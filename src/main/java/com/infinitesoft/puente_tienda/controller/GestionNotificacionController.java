package com.infinitesoft.puente_tienda.controller;

import com.infinitesoft.puente_tienda.dto.PlantillaNotificacionRequest;
import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import com.infinitesoft.puente_tienda.entities.PlantillaNotificacionPago;
import com.infinitesoft.puente_tienda.service.GestionNotificacionService;
import com.infinitesoft.puente_tienda.util.IconosPlantilla;
import com.infinitesoft.puente_tienda.util.LogMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
@Slf4j
public class GestionNotificacionController {

    private final GestionNotificacionService service;

    @GetMapping("/api/notificaciones-email")
    public List<NotificacionEmailPago> listar(
            @RequestParam(required = false) String estadoVista,
            @RequestParam(required = false) String q) {
        log.info("GET notificaciones-email estadoVista={} qLen={}",
                estadoVista != null ? estadoVista : "TODAS",
                q != null ? q.length() : 0);
        List<NotificacionEmailPago> list = service.listar(estadoVista, q);
        log.info("GET notificaciones-email resultado count={}", list.size());
        return list;
    }

    @PutMapping("/api/notificaciones-email/{id}/archivar")
    public NotificacionEmailPago archivar(@PathVariable Long id) {
        log.info("PUT archivar notificacion id={}", id);
        NotificacionEmailPago n = service.archivar(id);
        log.info("archivada id={} pagador={} ref={}",
                n.getId(),
                LogMask.nombre(n.getNombrePagador()),
                LogMask.referenciaCuenta(n.getReferenciaCuenta()));
        return n;
    }

    @DeleteMapping("/api/notificaciones-email/{id}")
    public ResponseEntity<?> eliminar(@PathVariable Long id) {
        log.info("DELETE notificacion id={}", id);
        service.eliminar(id);
        log.info("DELETE notificacion ok id={}", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/api/plantillas-notificacion-pago")
    public List<PlantillaNotificacionPago> listarPlantillas() {
        log.info("GET plantillas-notificacion-pago");
        return service.listarPlantillas();
    }

    @PostMapping("/api/plantillas-notificacion-pago")
    public PlantillaNotificacionPago crearPlantilla(@RequestBody PlantillaNotificacionRequest req) {
        log.info("POST plantilla nombre={}", req.getNombre());
        return service.crearPlantilla(req);
    }

    @PutMapping("/api/plantillas-notificacion-pago/{id}")
    public PlantillaNotificacionPago actualizarPlantilla(
            @PathVariable Long id,
            @RequestBody PlantillaNotificacionRequest req) {
        log.info("PUT plantilla id={} nombre={}", id, req.getNombre());
        return service.actualizarPlantilla(id, req);
    }

    @DeleteMapping("/api/plantillas-notificacion-pago/{id}")
    public ResponseEntity<?> eliminarPlantilla(@PathVariable Long id) {
        log.info("DELETE plantilla id={}", id);
        service.eliminarPlantilla(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/api/plantillas-notificacion-pago/iconos")
    public List<String> iconosDisponibles() {
        return IconosPlantilla.DISPONIBLES;
    }

    @GetMapping("/api/plantillas-notificacion-pago/iconos/{filename:.+}")
    public ResponseEntity<Resource> icono(@PathVariable String filename) {
        Resource res = service.iconoResource(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .body(res);
    }
}
