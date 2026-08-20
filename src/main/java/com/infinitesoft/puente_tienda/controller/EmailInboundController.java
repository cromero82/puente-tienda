package com.infinitesoft.puente_tienda.controller;

import com.infinitesoft.puente_tienda.config.WebConfig;
import com.infinitesoft.puente_tienda.dto.EmailInboundRequest;
import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import com.infinitesoft.puente_tienda.service.ConfirmacionPagoService;
import com.infinitesoft.puente_tienda.util.LogMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/email-inbound")
@RequiredArgsConstructor
@Slf4j
public class EmailInboundController {

    private final ConfirmacionPagoService service;
    private final WebConfig webConfig;

    @PostMapping
    public ResponseEntity<?> inbound(
            @RequestHeader(value = "X-Store-Key", required = false) String storeKey,
            @RequestBody EmailInboundRequest body) {
        if (storeKey == null || !storeKey.equals(webConfig.getStoreKey())) {
            log.warn("email-inbound rechazado: store-key inválido");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid store key"));
        }
        log.info(
                "email-inbound recibido messageId={} from={} to={} subject={} textLen={} htmlLen={}",
                LogMask.messageId(body != null ? body.getMessageId() : null),
                LogMask.email(body != null ? body.getFrom() : null),
                LogMask.email(body != null ? body.getTo() : null),
                LogMask.asunto(body != null ? body.getSubject() : null),
                body != null && body.getText() != null ? body.getText().length() : 0,
                body != null && body.getHtml() != null ? body.getHtml().length() : 0);
        NotificacionEmailPago saved = service.procesarInbound(body);
        log.info(
                "email-inbound ok id={} estadoVista={} monto={} pagador={} ref={} extraído: {}",
                saved.getId(),
                saved.getEstadoVista(),
                saved.getMonto(),
                LogMask.nombre(saved.getNombrePagador()),
                LogMask.referenciaCuenta(saved.getReferenciaCuenta()),
                LogMask.textoPlano(saved.getCuerpoTexto(), 2000));
        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "monto", saved.getMonto() != null ? saved.getMonto() : "",
                "estadoVista", saved.getEstadoVista(),
                "nombrePagador", saved.getNombrePagador() != null ? saved.getNombrePagador() : ""
        ));
    }
}
