package com.infinitesoft.puente_tienda.controller;

import com.infinitesoft.puente_tienda.config.WebConfig;
import com.infinitesoft.puente_tienda.dto.EmailInboundRequest;
import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import com.infinitesoft.puente_tienda.service.ConfirmacionPagoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/email-inbound")
@RequiredArgsConstructor
public class EmailInboundController {

    private final ConfirmacionPagoService service;
    private final WebConfig webConfig;

    @PostMapping
    public ResponseEntity<?> inbound(
            @RequestHeader(value = "X-Store-Key", required = false) String storeKey,
            @RequestBody EmailInboundRequest body) {
        if (storeKey == null || !storeKey.equals(webConfig.getStoreKey())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid store key"));
        }
        NotificacionEmailPago saved = service.procesarInbound(body);
        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "monto", saved.getMonto() != null ? saved.getMonto() : "",
                "estadoVista", saved.getEstadoVista(),
                "nombrePagador", saved.getNombrePagador() != null ? saved.getNombrePagador() : ""
        ));
    }
}
