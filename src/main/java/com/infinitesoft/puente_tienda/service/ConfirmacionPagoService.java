package com.infinitesoft.puente_tienda.service;

import com.infinitesoft.puente_tienda.dto.*;
import com.infinitesoft.puente_tienda.entities.*;
import com.infinitesoft.puente_tienda.parser.EmailPagoParser;
import com.infinitesoft.puente_tienda.repositories.*;
import com.infinitesoft.puente_tienda.util.LogMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final PlantillaNotificacionPagoRepository plantillaRepo;
    private final TicketSinNotificacionRepository ticketSinNotificacionRepo;
    private final MovimientoDesdeNotificacionService movimientoDesdeNotificacionService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public NotificacionEmailPago procesarInbound(EmailInboundRequest req) {
        if (req.getMessageId() != null && !req.getMessageId().isBlank()) {
            Optional<NotificacionEmailPago> existing = notificacionRepo.findByMessageId(req.getMessageId());
            if (existing.isPresent()) {
                log.info("Email inbound duplicado messageId={} id={} extraído: {}",
                        LogMask.messageId(req.getMessageId()),
                        existing.get().getId(),
                        LogMask.textoPlano(existing.get().getCuerpoTexto(), 2000));
                return existing.get();
            }
        }

        String inboundElegido = elegirCuerpoInbound(req.getText(), req.getHtml());
        String fuenteCuerpo = fuenteCuerpoInbound(req.getText(), req.getHtml(), inboundElegido);
        String cuerpoLimpio = EmailPagoParser.toPlainText(inboundElegido);
        Long metodoId = req.getMetodoPagoId();
        if (metodoId == null) {
            metodoId = metodoPagoRepo.findByPlantillaNotificacionPagoIsNotNull().stream()
                    .map(MetodoPago::getId)
                    .findFirst()
                    .orElse(2L);
        }

        EmailPagoParser.ParsedPago parsed = parseConPlantillas(cuerpoLimpio, metodoId);
        String cuerpoTexto = EmailPagoParser.resolverCuerpoTexto(cuerpoLimpio, parsed);

        String refEsperada = establecimientoRepo.findFirstByActivoTrueOrderByIdAsc()
                .map(Establecimiento::getReferenciaCuentaQr)
                .orElse(null);
        if (parsed != null && refEsperada != null && parsed.getReferenciaCuenta() != null
                && !refEsperada.equals(parsed.getReferenciaCuenta())) {
            log.warn("Referencia cuenta email {} != establecimiento {}",
                    LogMask.referenciaCuenta(parsed.getReferenciaCuenta()),
                    LogMask.referenciaCuenta(refEsperada));
        }

        NotificacionEmailPago notif = NotificacionEmailPago.builder()
                .messageId(req.getMessageId())
                .recibidoEn(LocalDateTime.now())
                .asunto(req.getSubject())
                .cuerpoRaw(firstNonBlank(req.getHtml(), req.getText(), ""))
                .cuerpoTexto(cuerpoTexto)
                .monto(parsed != null ? parsed.getMonto() : null)
                .nombrePagador(parsed != null ? parsed.getNombrePagador() : null)
                .referenciaCuenta(parsed != null ? parsed.getReferenciaCuenta() : null)
                .metodoPagoId(metodoId)
                .estadoVista("PENDIENTE")
                .plantillaNotificacionId(parsed != null ? parsed.getPlantillaId() : null)
                .plantillaNombre(parsed != null ? parsed.getPlantillaNombre() : null)
                .plantillaIcono(parsed != null ? parsed.getPlantillaIcono() : null)
                .build();
        notif = notificacionRepo.save(notif);
        String via = parsed != null && parsed.getPlantillaId() != null
                ? "plantilla"
                : (parsed != null ? "heurística" : "sin-extracción");
        log.info(
                "email-inbound extracción id={} via={} plantilla={} plantillaId={} fuente={} textLen={} htmlLen={} planoLen={} extraidoLen={} monto={} pagador={} ref={}",
                notif.getId(),
                via,
                notif.getPlantillaNombre() != null ? notif.getPlantillaNombre() : "-",
                notif.getPlantillaNotificacionId() != null ? notif.getPlantillaNotificacionId() : "-",
                fuenteCuerpo,
                req.getText() != null ? req.getText().length() : 0,
                req.getHtml() != null ? req.getHtml().length() : 0,
                cuerpoLimpio != null ? cuerpoLimpio.length() : 0,
                cuerpoTexto != null ? cuerpoTexto.length() : 0,
                notif.getMonto(),
                LogMask.nombre(notif.getNombrePagador()),
                LogMask.referenciaCuenta(notif.getReferenciaCuenta()));
        log.info("email-inbound cuerpo extraído id={}: {}",
                notif.getId(), LogMask.textoPlano(cuerpoTexto, 2000));
        if (parsed == null || parsed.getPlantillaId() == null) {
            log.info("email-inbound cuerpo plano (sin HTML, preview) id={}: {}",
                    notif.getId(), LogMask.textoPlano(cuerpoLimpio, 800));
        }
        log.info(
                "BD notificacion_email_pago insert id={} estadoVista={} monto={} pagador={} ref={} metodoPagoId={} plantilla={}",
                notif.getId(),
                notif.getEstadoVista(),
                notif.getMonto(),
                LogMask.nombre(notif.getNombrePagador()),
                LogMask.referenciaCuenta(notif.getReferenciaCuenta()),
                notif.getMetodoPagoId(),
                notif.getPlantillaNombre());

        PlantillaNotificacionPago plantillaMatch = parsed != null && parsed.getPlantillaId() != null
                ? plantillaRepo.findById(parsed.getPlantillaId()).orElse(null)
                : null;
        boolean plantillaLedger = movimientoDesdeNotificacionService.esPlantillaDeMovimiento(plantillaMatch);
        if (plantillaLedger) {
            movimientoDesdeNotificacionService.registrarSiAplica(notif, plantillaMatch);
        } else if (parsed != null && parsed.getMonto() != null) {
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
        return toDto(h, n.getId(), false, null, new HashMap<>());
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
        log.info(
                "BD match CONFIRMADA historialElectronicoId={} notificacionId={} monto={} pagador={}",
                h.getId(),
                n.getId(),
                h.getMontoEsperado(),
                LogMask.nombre(h.getNombrePagador()));
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
        Map<Long, Object[]> snapCache = new HashMap<>();

        for (HistorialReciboElectronico h : creada) {
            out.add(toDto(h, null, false, null, snapCache));
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
            out.add(toDto(h, notifId, true, cands, snapCache));
        }

        for (HistorialReciboElectronico h : confirmada) {
            Optional<NotificacionEmailPago> nOpt =
                    notificacionRepo.findFirstByHistorialReciboElectronicoIdOrderByRecibidoEnDesc(h.getId());
            if (nOpt.isPresent() && "MOSTRADA".equals(nOpt.get().getEstadoVista())) {
                continue; // ya vista en panel
            }
            Long notifId = nOpt.map(NotificacionEmailPago::getId).orElse(null);
            out.add(toDto(h, notifId, false, null, snapCache));
        }
        return out;
    }

    @Transactional
    public PendienteConfirmacionDto marcarYaNoEsperar(Long historialElectronicoId) {
        HistorialReciboElectronico h = historialElectronicoRepo.findById(historialElectronicoId)
                .orElseThrow(() -> new IllegalArgumentException("historial electrónico no encontrado"));
        String previo = h.getEstado();
        if (!"CREADA".equals(previo) && !"AMBIGUA".equals(previo) && !"HUERFANA".equals(previo)) {
            throw new IllegalArgumentException("solo se puede dejar de esperar un pago en espera o ambiguo");
        }
        h.setEstado("HUERFANA");
        historialElectronicoRepo.save(h);

        if ("AMBIGUA".equals(previo)) {
            List<HistorialReciboElectronico> restantes =
                    historialElectronicoRepo.findByEstadoAndMontoEsperado("AMBIGUA", h.getMontoEsperado());
            if (restantes.size() == 1) {
                HistorialReciboElectronico other = restantes.get(0);
                other.setEstado("CREADA");
                historialElectronicoRepo.save(other);
                log.info("BD historial_recibos_electronicos AMBIGUA→CREADA id={} tras HUERFANA id={}",
                        other.getId(), h.getId());
            }
        }

        upsertTicketSinNotificacion(h);
        log.info("BD historial_recibos_electronicos HUERFANA id={} reciboId={} monto={}",
                h.getId(), h.getHistorialReciboId(), h.getMontoEsperado());
        return toDto(h, null, false, null, new HashMap<>());
    }

    @Transactional(readOnly = true)
    public List<TicketSinNotificacion> listarTicketsSinNotificacion() {
        return ticketSinNotificacionRepo.findAllByOrderByMarcadoEnDesc();
    }

    private void upsertTicketSinNotificacion(HistorialReciboElectronico h) {
        if (ticketSinNotificacionRepo.findByHistorialReciboId(h.getHistorialReciboId()).isPresent()) {
            return;
        }
        Object[] snap = loadVentaSnapshot(h.getHistorialReciboId());
        BigDecimal valor = h.getMontoEsperado();
        LocalDateTime fecha = h.getFechaCreacion() != null ? h.getFechaCreacion() : LocalDateTime.now();
        String persona = h.getNombrePagador();
        String numeroVenta = null;
        if (snap != null) {
            if (snap[1] instanceof BigDecimal) {
                valor = (BigDecimal) snap[1];
            }
            LocalDateTime fechaSnap = toLocalDateTime(snap[2]);
            if (fechaSnap != null) {
                fecha = fechaSnap;
            }
            if (snap[3] != null && !String.valueOf(snap[3]).isBlank()) {
                persona = String.valueOf(snap[3]).trim();
            }
            if (snap[4] != null && !String.valueOf(snap[4]).isBlank()) {
                numeroVenta = String.valueOf(snap[4]).trim();
            }
        }
        TicketSinNotificacion row = TicketSinNotificacion.builder()
                .historialReciboId(h.getHistorialReciboId())
                .historialReciboElectronicoId(h.getId())
                .numeroVenta(numeroVenta)
                .valor(valor)
                .fecha(fecha)
                .persona(persona)
                .marcadoEn(LocalDateTime.now())
                .build();
        ticketSinNotificacionRepo.save(row);
        log.info("BD ticket_sin_notificacion INSERT reciboId={} numeroVenta={} valor={}",
                h.getHistorialReciboId(), numeroVenta, valor);
    }

    private Object[] loadVentaSnapshot(Long historialReciboId) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT hr.id, hr.total, hr.fecha_creacion, "
                                + "COALESCE(NULLIF(TRIM(c.nombre), ''), NULLIF(TRIM(cdv.nombre), '')), "
                                + "dv.consecutivo "
                                + "FROM historial_recibo hr "
                                + "LEFT JOIN client c ON c.id = hr.cliente_id "
                                + "LEFT JOIN documento_venta dv ON dv.historial_recibo_id = hr.id "
                                + "LEFT JOIN client cdv ON cdv.id = dv.cliente_id "
                                + "WHERE hr.id = :id")
                .setParameter("id", historialReciboId)
                .getResultList();
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        Object row = rows.get(0);
        if (row instanceof Object[]) {
            return (Object[]) row;
        }
        return new Object[]{row};
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        return null;
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
                        log.info("BD notificacion_email_pago MOSTRADA id={} pagador={}",
                                n.getId(), LogMask.nombre(n.getNombrePagador()));
                    });
        }
    }

    private PendienteConfirmacionDto toDto(HistorialReciboElectronico h, Long notificacionId,
                                           boolean ambiguo, List<CandidatoAmbiguoDto> cands,
                                           Map<Long, Object[]> snapCache) {
        String nombreCliente = blankToNull(h.getNombreCliente());
        String numeroVenta = null;
        Long reciboId = h.getHistorialReciboId();
        if (reciboId != null) {
            Object[] snap = snapCache.computeIfAbsent(reciboId, this::loadVentaSnapshot);
            if (snap != null) {
                if (nombreCliente == null && snap.length > 3 && snap[3] != null
                        && !String.valueOf(snap[3]).isBlank()) {
                    nombreCliente = String.valueOf(snap[3]).trim();
                }
                if (snap.length > 4 && snap[4] != null && !String.valueOf(snap[4]).isBlank()) {
                    numeroVenta = String.valueOf(snap[4]).trim();
                }
            }
        }
        if (esNombreAnonimo(nombreCliente)) {
            nombreCliente = null;
        }
        return PendienteConfirmacionDto.builder()
                .id(h.getId())
                .historialReciboId(h.getHistorialReciboId())
                .sesionId(h.getSesionId())
                .metodoPagoId(h.getMetodoPagoId())
                .montoEsperado(h.getMontoEsperado())
                .estado(h.getEstado())
                .nombrePagador(h.getNombrePagador())
                .nombreCliente(nombreCliente)
                .numeroVenta(numeroVenta)
                .fechaCreacion(h.getFechaCreacion())
                .fechaConfirmacion(h.getFechaConfirmacion())
                .notificacionId(notificacionId)
                .ambiguo(ambiguo)
                .candidatos(cands)
                .build();
    }

    private EmailPagoParser.ParsedPago parseConPlantillas(String cuerpoLimpio, Long metodoId) {
        List<PlantillaNotificacionPago> plantillas = plantillaRepo.findByActivoTrueOrderByOrdenAscIdAsc();
        for (PlantillaNotificacionPago p : plantillas) {
            EmailPagoParser.ParsedPago attempt =
                    EmailPagoParser.parseTemplateOnly(cuerpoLimpio, p.getCuerpo(), metodoId);
            if (attempt != null) {
                attempt.setPlantillaId(p.getId());
                attempt.setPlantillaNombre(p.getNombre());
                attempt.setPlantillaIcono(p.getIcono());
                log.info("email-inbound match plantilla={} id={} extraído: {}",
                        p.getNombre(), p.getId(), LogMask.textoPlano(attempt.getFragmento(), 2000));
                return attempt;
            }
        }
        if (!plantillas.isEmpty()) {
            log.info("email-inbound sin match de plantilla (activas={}), usando heurística. plano: {}",
                    plantillas.size(), LogMask.textoPlano(cuerpoLimpio, 800));
        }
        return EmailPagoParser.parse(cuerpoLimpio, null, metodoId);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /**
     * Prefiere el cuerpo más útil para plantillas. Muchos clientes (Outlook/CF)
     * mandan un {@code text/plain} corto o vacío de sentido y el HTML completo;
     * si se prioriza text, no matchea «Hiciste un pago a… por $…».
     */
    static String elegirCuerpoInbound(String text, String html) {
        String t = text == null ? "" : text.trim();
        String h = html == null ? "" : html.trim();
        if (h.isEmpty()) {
            return t;
        }
        if (t.isEmpty()) {
            return h;
        }
        // Señal de correo de pago/compra en HTML y no en text → HTML
        String tLower = t.toLowerCase();
        String hLower = h.toLowerCase();
        boolean htmlParecePago = hLower.contains("hiciste un pago")
                || hLower.contains("realizaste una compra")
                || hLower.contains("recibiste un pago")
                || hLower.contains("recibiste una transferencia")
                || hLower.contains("por $");
        boolean textParecePago = tLower.contains("hiciste un pago")
                || tLower.contains("realizaste una compra")
                || tLower.contains("recibiste un pago")
                || tLower.contains("recibiste una transferencia")
                || tLower.contains("por $");
        if (htmlParecePago && !textParecePago) {
            return h;
        }
        if (h.length() >= t.length()) {
            return h;
        }
        return t;
    }

    static String fuenteCuerpoInbound(String text, String html, String elegido) {
        if (elegido == null || elegido.isBlank()) {
            return "vacio";
        }
        String t = text == null ? "" : text.trim();
        String h = html == null ? "" : html.trim();
        if (!h.isEmpty() && elegido.equals(h)) {
            return "html";
        }
        if (!t.isEmpty() && elegido.equals(t)) {
            return "text";
        }
        return "mixto";
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean esNombreAnonimo(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return true;
        }
        String n = nombre.trim();
        return n.equalsIgnoreCase("anonimo") || n.equalsIgnoreCase("anónimo");
    }
}
