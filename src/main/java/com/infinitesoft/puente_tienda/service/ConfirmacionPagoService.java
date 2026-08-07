package com.infinitesoft.puente_tienda.service;

import com.infinitesoft.puente_tienda.dto.*;
import com.infinitesoft.puente_tienda.entities.*;
import com.infinitesoft.puente_tienda.parser.EmailPagoParser;
import com.infinitesoft.puente_tienda.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfirmacionPagoService {

    private final NotificacionEmailPagoRepository notificacionRepo;
    private final HistorialReciboElectronicoRepository historialElectronicoRepo;
    private final MetodoPagoRepository metodoPagoRepo;
    private final EstablecimientoRepository establecimientoRepo;

    @Transactional
    public NotificacionEmailPago procesarInbound(EmailInboundRequest req) {
        if (req.getMessageId() != null && !req.getMessageId().isBlank()) {
            Optional<NotificacionEmailPago> existing = notificacionRepo.findByMessageId(req.getMessageId());
            if (existing.isPresent()) {
                log.info("Email inbound duplicado messageId={}", req.getMessageId());
                return existing.get();
            }
        }

        String cuerpo = firstNonBlank(req.getText(), stripHtml(req.getHtml()), "");
        Long metodoId = req.getMetodoPagoId();
        String plantilla = null;

        if (metodoId != null) {
            plantilla = metodoPagoRepo.findById(metodoId)
                    .map(MetodoPago::getPlantillaNotificacionPago)
                    .orElse(null);
        } else {
            List<MetodoPago> conPlantilla = metodoPagoRepo.findByPlantillaNotificacionPagoIsNotNull();
            if (!conPlantilla.isEmpty()) {
                MetodoPago mp = conPlantilla.get(0);
                metodoId = mp.getId();
                plantilla = mp.getPlantillaNotificacionPago();
            }
        }

        EmailPagoParser.ParsedPago parsed = EmailPagoParser.parse(cuerpo, plantilla, metodoId);

        String refEsperada = establecimientoRepo.findFirstByActivoTrueOrderByIdAsc()
                .map(Establecimiento::getReferenciaCuentaQr)
                .orElse(null);
        if (parsed != null && refEsperada != null && parsed.getReferenciaCuenta() != null
                && !refEsperada.equals(parsed.getReferenciaCuenta())) {
            log.warn("Referencia cuenta email {} != establecimiento {}",
                    parsed.getReferenciaCuenta(), refEsperada);
        }

        NotificacionEmailPago notif = NotificacionEmailPago.builder()
                .messageId(req.getMessageId())
                .recibidoEn(LocalDateTime.now())
                .asunto(req.getSubject())
                .cuerpoRaw(req.getHtml() != null ? req.getHtml() : req.getText())
                .cuerpoTexto(cuerpo)
                .monto(parsed != null ? parsed.getMonto() : null)
                .nombrePagador(parsed != null ? parsed.getNombrePagador() : null)
                .referenciaCuenta(parsed != null ? parsed.getReferenciaCuenta() : null)
                .metodoPagoId(metodoId)
                .estadoVista("PENDIENTE")
                .build();
        notif = notificacionRepo.save(notif);

        if (parsed != null && parsed.getMonto() != null) {
            intentarMatchAutomatico(notif, parsed.getMonto(), parsed.getNombrePagador());
        }
        return notif;
    }

    private void intentarMatchAutomatico(NotificacionEmailPago notif, BigDecimal monto, String nombrePagador) {
        List<HistorialReciboElectronico> candidatos =
                historialElectronicoRepo.findByEstadoAndMontoEsperado("CREADA", monto);

        if (candidatos.isEmpty()) {
            log.info("Sin recibo electrónico CREADA para monto {}", monto);
            return;
        }
        if (candidatos.size() > 1) {
            log.info("Ambigüedad: {} CREADA con monto {}", candidatos.size(), monto);
            candidatos.forEach(c -> {
                c.setEstado("AMBIGUA");
                historialElectronicoRepo.save(c);
            });
            return;
        }

        confirmar(candidatos.get(0), notif, nombrePagador);
    }

    @Transactional
    public PendienteConfirmacionDto asignar(Long historialElectronicoId, Long notificacionId) {
        HistorialReciboElectronico h = historialElectronicoRepo.findById(historialElectronicoId)
                .orElseThrow(() -> new IllegalArgumentException("historial electrónico no encontrado"));
        NotificacionEmailPago n = notificacionRepo.findById(notificacionId)
                .orElseThrow(() -> new IllegalArgumentException("notificación no encontrada"));

        // Resolver hermanas AMBIGUA del mismo monto → dejarlas CREADA si quedan sin match
        BigDecimal monto = h.getMontoEsperado();
        confirmar(h, n, n.getNombrePagador());
        historialElectronicoRepo.findByEstadoAndMontoEsperado("AMBIGUA", monto).forEach(other -> {
            if (!other.getId().equals(h.getId())) {
                other.setEstado("CREADA");
                historialElectronicoRepo.save(other);
            }
        });
        return toDto(h, n.getId(), false, null);
    }

    private void confirmar(HistorialReciboElectronico h, NotificacionEmailPago n, String nombrePagador) {
        h.setEstado("CONFIRMADA");
        h.setFechaConfirmacion(LocalDateTime.now());
        if (nombrePagador != null) {
            h.setNombrePagador(nombrePagador);
        }
        historialElectronicoRepo.save(h);

        // estado_vista sigue PENDIENTE hasta que el FE termine el countdown → MOSTRADA
        n.setHistorialReciboElectronicoId(h.getId());
        if (nombrePagador != null) {
            n.setNombrePagador(nombrePagador);
        }
        notificacionRepo.save(n);
    }

    @Transactional(readOnly = true)
    public List<PendienteConfirmacionDto> listarPendientes(Long sesionId) {
        List<HistorialReciboElectronico> creada =
                historialElectronicoRepo.findByEstadoAndSesionIdOrderByFechaCreacionAsc("CREADA", sesionId);
        List<HistorialReciboElectronico> ambigua =
                historialElectronicoRepo.findByEstadoAndSesionIdOrderByFechaCreacionAsc("AMBIGUA", sesionId);
        List<HistorialReciboElectronico> confirmada =
                historialElectronicoRepo.findByEstadoAndSesionIdOrderByFechaCreacionAsc("CONFIRMADA", sesionId);

        List<PendienteConfirmacionDto> out = new ArrayList<>();

        for (HistorialReciboElectronico h : creada) {
            out.add(toDto(h, null, false, null));
        }

        for (HistorialReciboElectronico h : ambigua) {
            Long notifId = notificacionRepo.findByMontoAndEstadoVista(h.getMontoEsperado(), "PENDIENTE")
                    .stream()
                    .filter(n -> n.getHistorialReciboElectronicoId() == null)
                    .map(NotificacionEmailPago::getId)
                    .findFirst()
                    .orElse(null);
            String nombreSugerido = notificacionRepo.findByMontoAndEstadoVista(h.getMontoEsperado(), "PENDIENTE")
                    .stream()
                    .map(NotificacionEmailPago::getNombrePagador)
                    .filter(x -> x != null && !x.isBlank())
                    .findFirst()
                    .orElse(null);
            List<HistorialReciboElectronico> mismos =
                    historialElectronicoRepo.findByEstadoAndMontoEsperado("AMBIGUA", h.getMontoEsperado());
            String finalNombre = nombreSugerido;
            List<CandidatoAmbiguoDto> cands = mismos.stream()
                    .map(x -> CandidatoAmbiguoDto.builder()
                            .historialElectronicoId(x.getId())
                            .historialReciboId(x.getHistorialReciboId())
                            .montoEsperado(x.getMontoEsperado())
                            .nombrePagadorSugerido(finalNombre)
                            .build())
                    .collect(Collectors.toList());
            out.add(toDto(h, notifId, true, cands));
        }

        for (HistorialReciboElectronico h : confirmada) {
            Optional<NotificacionEmailPago> nOpt =
                    notificacionRepo.findFirstByHistorialReciboElectronicoIdOrderByRecibidoEnDesc(h.getId());
            if (nOpt.isPresent() && "MOSTRADA".equals(nOpt.get().getEstadoVista())) {
                continue; // ya vista en panel
            }
            Long notifId = nOpt.map(NotificacionEmailPago::getId).orElse(null);
            out.add(toDto(h, notifId, false, null));
        }
        return out;
    }

    @Transactional
    public void marcarConfirmadas(List<Long> historialElectronicoIds) {
        if (historialElectronicoIds == null) {
            return;
        }
        for (Long id : historialElectronicoIds) {
            notificacionRepo.findFirstByHistorialReciboElectronicoIdOrderByRecibidoEnDesc(id)
                    .ifPresent(n -> {
                        n.setEstadoVista("MOSTRADA");
                        notificacionRepo.save(n);
                    });
        }
    }

    private PendienteConfirmacionDto toDto(HistorialReciboElectronico h, Long notificacionId,
                                           boolean ambiguo, List<CandidatoAmbiguoDto> cands) {
        return PendienteConfirmacionDto.builder()
                .id(h.getId())
                .historialReciboId(h.getHistorialReciboId())
                .sesionId(h.getSesionId())
                .metodoPagoId(h.getMetodoPagoId())
                .montoEsperado(h.getMontoEsperado())
                .estado(h.getEstado())
                .nombrePagador(h.getNombrePagador())
                .fechaCreacion(h.getFechaCreacion())
                .fechaConfirmacion(h.getFechaConfirmacion())
                .notificacionId(notificacionId)
                .ambiguo(ambiguo)
                .candidatos(cands)
                .build();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        return html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
