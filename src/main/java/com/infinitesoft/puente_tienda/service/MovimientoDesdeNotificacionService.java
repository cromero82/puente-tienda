package com.infinitesoft.puente_tienda.service;

import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import com.infinitesoft.puente_tienda.entities.PlantillaNotificacionPago;
import com.infinitesoft.puente_tienda.parser.EmailPagoParser;
import com.infinitesoft.puente_tienda.repositories.NotificacionEmailPagoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tras match de plantilla con OF configurados, escribe el ledger
 * {@code movimiento_origen_fondos} (misma BD que el POS).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MovimientoDesdeNotificacionService {

    static final String ORIGEN_TIPO_DEFAULT = "MOVIMIENTO BANCO POR IDENTIFICAR";
    /** Segundo movimiento: sale de «Para ordenar» (u otra bolsa) hacia el bolsillo destino. */
    static final String ORIGEN_TIPO_LEGALIZACION = "LEGALIZACION_NOTIFICACION";
    /** Ajuste al confirmar QR con monto email ≠ esperado (neto = esperado ya contabilizado). */
    static final String ORIGEN_TIPO_QR_MONTO_DISTINTO = "QR_MONTO_DISTINTO";

    private final NotificacionEmailPagoRepository notificacionRepo;
    private final FaltanteQrCreditoService faltanteQrCreditoService;

    @PersistenceContext
    private EntityManager entityManager;

    public boolean esPlantillaDeMovimiento(PlantillaNotificacionPago p) {
        if (p == null) {
            return false;
        }
        if (p.getNaturaleza() != null && !p.getNaturaleza().isBlank()) {
            return true;
        }
        if (p.getOrigenFondosOrigenId() != null || p.getOrigenFondosDestinoId() != null) {
            return true;
        }
        String nombre = p.getNombre() != null ? p.getNombre().toUpperCase() : "";
        return nombre.contains("EGRESO") || nombre.contains("INGRESO");
    }

    @Transactional
    public int registrarPendientesDePlantilla(PlantillaNotificacionPago plantilla) {
        if (plantilla == null || plantilla.getId() == null) {
            return 0;
        }
        List<NotificacionEmailPago> notifs =
                notificacionRepo.findByPlantillaNotificacionIdOrderByIdAsc(plantilla.getId());
        int creados = 0;
        for (NotificacionEmailPago n : notifs) {
            if (registrarSiAplica(n, plantilla)) {
                creados++;
            }
        }
        if (creados > 0) {
            log.info("backfill movimientos plantilla={} id={} creados={}",
                    plantilla.getNombre(), plantilla.getId(), creados);
        }
        return creados;
    }

    /**
     * Rematch de notificaciones sin plantilla (p.ej. llegaron antes de crear/ajustar la plantilla
     * o el inbound usó un text/plain pobre). Actualiza campos parseados y contabiliza si aplica.
     */
    @Transactional
    public int vincularYContabilizarSinPlantilla(PlantillaNotificacionPago plantilla) {
        if (plantilla == null || plantilla.getId() == null || plantilla.getCuerpo() == null) {
            return 0;
        }
        List<NotificacionEmailPago> huérfanas = notificacionRepo.findSinPlantillaNoArchivadas();
        int vinculadas = 0;
        int movimientos = 0;
        for (NotificacionEmailPago n : huérfanas) {
            String cuerpo = firstNonBlank(n.getCuerpoRaw(), n.getCuerpoTexto());
            EmailPagoParser.ParsedPago parsed =
                    EmailPagoParser.parseTemplateOnly(cuerpo, plantilla.getCuerpo(), n.getMetodoPagoId());
            if (parsed == null || parsed.getMonto() == null) {
                continue;
            }
            n.setPlantillaNotificacionId(plantilla.getId());
            n.setPlantillaNombre(plantilla.getNombre());
            n.setPlantillaIcono(plantilla.getIcono());
            n.setMonto(parsed.getMonto());
            if (parsed.getNombrePagador() != null && !parsed.getNombrePagador().isBlank()) {
                n.setNombrePagador(parsed.getNombrePagador());
            }
            if (parsed.getReferenciaCuenta() != null) {
                n.setReferenciaCuenta(parsed.getReferenciaCuenta());
            }
            if (parsed.getFragmento() != null && !parsed.getFragmento().isBlank()) {
                n.setCuerpoTexto(parsed.getFragmento());
            }
            notificacionRepo.save(n);
            vinculadas++;
            if (registrarSiAplica(n, plantilla)) {
                movimientos++;
            }
        }
        if (vinculadas > 0) {
            log.info(
                    "rematch plantilla={} id={} notifsVinculadas={} movimientosCreados={}",
                    plantilla.getNombre(), plantilla.getId(), vinculadas, movimientos);
        }
        return vinculadas;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null ? b : "";
    }

    /**
     * @return true si se insertó al menos un movimiento
     */
    @Transactional
    public boolean registrarSiAplica(NotificacionEmailPago notif, PlantillaNotificacionPago plantilla) {
        if (notif == null || notif.getId() == null || plantilla == null) {
            return false;
        }
        if (notif.getMonto() == null || notif.getMonto().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("notif {} plantilla {}: monto inválido, no se genera movimiento",
                    notif.getId(), plantilla.getId());
            return false;
        }

        Integer origenId = plantilla.getOrigenFondosOrigenId();
        Integer destinoId = plantilla.getOrigenFondosDestinoId();
        if (origenId == null && destinoId == null) {
            log.warn(
                    "notif {} matcheó plantilla {} ({}) pero no hay origen/destino OF: no se genera movimiento",
                    notif.getId(), plantilla.getId(), plantilla.getNombre());
            return false;
        }

        String origenTipo = blankToDefault(plantilla.getOrigenTipo(), ORIGEN_TIPO_DEFAULT);
        if (yaExiste(origenTipo, notif.getId())) {
            log.info("notif {} ya tiene movimiento origenTipo={} — skip", notif.getId(), origenTipo);
            return false;
        }

        String naturaleza = resolverNaturaleza(plantilla);
        String usuarioId = resolverUsuarioSistema();
        LocalDate fecha = notif.getRecibidoEn() != null
                ? notif.getRecibidoEn().toLocalDate()
                : LocalDate.now();
        String tercero = trunc(notif.getNombrePagador(), 150);
        String observacion = trunc(
                "Email #" + notif.getId() + " · " + plantilla.getNombre()
                        + (notif.getCuerpoTexto() != null ? " · " + notif.getCuerpoTexto() : ""),
                500);
        BigDecimal valor = notif.getMonto();

        if (origenId != null && destinoId != null && !origenId.equals(destinoId)) {
            insertarTraslado(origenId, destinoId, valor, fecha, usuarioId, tercero, observacion,
                    origenTipo, notif.getId(), null);
            return true;
        }

        Integer cuentaId = origenId != null ? origenId : destinoId;
        if ("INGRESO".equals(naturaleza)) {
            insertarSimple(cuentaId, "ENTRADA_MANUAL", valor, valor, fecha, usuarioId, tercero,
                    observacion, origenTipo, notif.getId(), null);
        } else {
            insertarSimple(cuentaId, "SALIDA_EGRESO", valor, valor.negate(), fecha, usuarioId, tercero,
                    observacion, origenTipo, notif.getId(), null);
        }
        return true;
    }

    /**
     * Reclasifica el saldo que quedó en la bolsa «por identificar» (p.ej. OF «Para ordenar»)
     * hacia el bolsillo de responsabilidad (Personal, Nómina, gasto…).
     *
     * @return true si se insertó traslado de legalización
     */
    @Transactional
    public boolean legalizarEnLedger(
            NotificacionEmailPago notif,
            PlantillaNotificacionPago plantilla,
            String clasificacion,
            Integer destinoOfOverride,
            String observacionExtra
    ) {
        if (notif == null || notif.getId() == null || notif.getMonto() == null
                || notif.getMonto().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (yaExiste(ORIGEN_TIPO_LEGALIZACION, notif.getId())) {
            log.info("notif {} ya tiene LEGALIZACION_NOTIFICACION — skip ledger", notif.getId());
            return false;
        }
        if (!tieneMovimientoPorIdentificar(notif.getId())) {
            log.info("notif {} sin movimiento POR IDENTIFICAR — solo clasificación", notif.getId());
            return false;
        }

        Integer bolsaId = resolverBolsaPorIdentificar(notif, plantilla);
        Integer destinoId = destinoOfOverride != null
                ? destinoOfOverride
                : resolverDestinoPorClasificacion(clasificacion);
        if (bolsaId == null) {
            throw new IllegalArgumentException(
                    "No se encontró la bolsa «por identificar» (p.ej. Sin Clasificar). "
                            + "Revise la plantilla o el movimiento de la notificación #" + notif.getId());
        }
        if (destinoId == null) {
            if ("OTRO_LEGALIZADO".equalsIgnoreCase(clasificacion)) {
                log.info("notif {} OTRO_LEGALIZADO sin destino OF — solo clasificación", notif.getId());
                return false;
            }
            throw new IllegalArgumentException(
                    "Indique el origen de fondos destino para legalizar como «"
                            + clasificacion + "» (p.ej. Personal administrador, Bolsillo Nómina, Arriendo).");
        }
        if (bolsaId.equals(destinoId)) {
            throw new IllegalArgumentException(
                    "El destino de legalización no puede ser la misma bolsa por identificar (OF id="
                            + bolsaId + ").");
        }

        Integer motivoId = motivoIdPorClasificacion(clasificacion);
        String usuarioId = resolverUsuarioSistema();
        LocalDate fecha = notif.getRecibidoEn() != null
                ? notif.getRecibidoEn().toLocalDate()
                : LocalDate.now();
        String tercero = trunc(notif.getNombrePagador(), 150);
        String observacion = trunc(
                "Legalizar #" + notif.getId() + " · " + clasificacion
                        + (observacionExtra != null && !observacionExtra.isBlank()
                        ? " · " + observacionExtra.trim() : ""),
                500);

        insertarTraslado(bolsaId, destinoId, notif.getMonto(), fecha, usuarioId, tercero, observacion,
                ORIGEN_TIPO_LEGALIZACION, notif.getId(), motivoId, clasificacion);
        log.info("BD LEGALIZACION notif={} bolsaOf={} destinoOf={} clasificacion={} valor={}",
                notif.getId(), bolsaId, destinoId, clasificacion, notif.getMonto());
        return true;
    }

    /**
     * Tras confirmar match QR con monto distinto al esperado.
     * <ul>
     *   <li>Sobrepago: +diff en OF del medio QR; −diff en {@code origenFondosDevolucionId}
     *       (caja u otro OF elegido — el efectivo que se le devolvió al cliente).
     *       Ambos como {@code AJUSTE_SALDO} (no {@code SALIDA_EGRESO}): el corte excluye
     *       egresos formales de la columna movimientos; esta devolución no es un egreso documento.</li>
     *   <li>Faltante: −diff en OF del medio QR (no llegó al banco) y deuda CxC
     *       (crear o reabrir saldo) por el faltante.</li>
     * </ul>
     */
    @Transactional
    /**
     * @return reapertura de ticket para CxC manual (venta faltante); null en sobrepago / abono CxC
     */
    public com.infinitesoft.puente_tienda.dto.FaltanteReaperturaResult registrarAjusteMontoDistintoQr(
            Long metodoPagoId,
            BigDecimal montoEsperado,
            BigDecimal montoRecibido,
            Long historialElectronicoId,
            String tercero,
            Integer origenFondosDevolucionId,
            Long historialReciboId,
            Long abonoCxcId
    ) {
        if (historialElectronicoId == null || montoEsperado == null || montoRecibido == null) {
            return null;
        }
        BigDecimal diff = montoRecibido.subtract(montoEsperado);
        if (diff.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        if (yaExiste(ORIGEN_TIPO_QR_MONTO_DISTINTO, historialElectronicoId)) {
            log.info("hre {} ya tiene ajuste QR_MONTO_DISTINTO — skip", historialElectronicoId);
            return null;
        }
        Integer cuentaQrId = findOrigenFondosIdByMetodoPago(metodoPagoId);
        if (cuentaQrId == null) {
            log.warn("hre {} sin OF para metodoPagoId={} — no se ajusta diferencia QR",
                    historialElectronicoId, metodoPagoId);
            return null;
        }
        String usuarioId = resolverUsuarioSistema();
        LocalDate fecha = LocalDate.now();
        String terceroT = trunc(tercero, 150);
        BigDecimal abs = diff.abs();
        if (diff.compareTo(BigDecimal.ZERO) > 0) {
            if (origenFondosDevolucionId == null) {
                throw new IllegalArgumentException(
                        "Debe indicar el origen de fondos de la devolución (caja u otro).");
            }
            if (!cuentaExiste(origenFondosDevolucionId)) {
                throw new IllegalArgumentException(
                        "Origen de fondos de devolución no existe: " + origenFondosDevolucionId);
            }
            // Banco recibió de más → el exceso queda en el OF del medio electrónico
            insertarSimple(cuentaQrId, "AJUSTE_SALDO", abs, abs, fecha, usuarioId, terceroT,
                    trunc("Sobrepago QR · esperado " + montoEsperado + " · recibido " + montoRecibido, 500),
                    ORIGEN_TIPO_QR_MONTO_DISTINTO, historialElectronicoId, null);
            // Devolución en efectivo (u otro OF) — AJUSTE_SALDO para que sume en corte.movimientos
            insertarSimple(origenFondosDevolucionId, "AJUSTE_SALDO", abs, abs.negate(), fecha, usuarioId, terceroT,
                    trunc("Devolución al cliente por sobrepago QR · " + abs, 500),
                    ORIGEN_TIPO_QR_MONTO_DISTINTO, historialElectronicoId, null);
            log.info("BD QR_MONTO_DISTINTO sobrepago hre={} diff={} ofQr={} ofDevolucion={}",
                    historialElectronicoId, abs, cuentaQrId, origenFondosDevolucionId);
            return null;
        }

        // Faltante
        if (abonoCxcId != null) {
            // Abono CxC: esperado ya entró a OF → baja diferencia (ajuste, no egreso documento).
            insertarSimple(cuentaQrId, "AJUSTE_SALDO", abs, abs.negate(), fecha, usuarioId, terceroT,
                    trunc("Pago QR incompleto (abono) · faltante " + abs
                            + " · esperado " + montoEsperado + " · recibido " + montoRecibido, 500),
                    ORIGEN_TIPO_QR_MONTO_DISTINTO, historialElectronicoId, null);
            Long cxcId = registrarCxcFaltanteQr(
                    abs, null, abonoCxcId, historialElectronicoId, terceroT, usuarioId);
            log.info("BD QR_MONTO_DISTINTO faltante-abono hre={} diff={} cxcId={}",
                    historialElectronicoId, abs, cxcId);
            return null;
        }

        // Venta: reabre ticket vivo; CxC se abre en FE con modal Generar crédito (abono=recibido).
        com.infinitesoft.puente_tienda.dto.FaltanteReaperturaResult reapertura =
                faltanteQrCreditoService.reabrirTicketParaCreditoManual(
                        abs,
                        montoRecibido,
                        montoEsperado,
                        historialReciboId,
                        historialElectronicoId,
                        metodoPagoId);
        log.info("BD QR_MONTO_DISTINTO faltante-venta→ticket reabierto hre={} ticket={}",
                historialElectronicoId, reapertura != null ? reapertura.getTicketId() : null);
        return reapertura;
    }

    /**
     * Solo abonos CxC: incrementa saldo de la cuenta existente.
     */
    private Long registrarCxcFaltanteQr(
            BigDecimal faltante,
            Long historialReciboIdIgnored,
            Long abonoCxcId,
            Long historialElectronicoId,
            String tercero,
            String usuarioId
    ) {
        if (faltante == null || faltante.compareTo(BigDecimal.ZERO) <= 0 || abonoCxcId == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "SELECT cuenta_por_cobrar_id FROM abono_cxc WHERE id = :id")
                .setParameter("id", abonoCxcId)
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            log.warn("abono_cxc #{} no encontrado para faltante QR", abonoCxcId);
            return null;
        }
        Long cxcId = ((Number) rows.get(0)).longValue();
        entityManager.createNativeQuery(
                        "UPDATE cuenta_por_cobrar SET "
                                + "saldo_pendiente = saldo_pendiente + :faltante, "
                                + "estado = CASE WHEN estado IN ('PAGADA','ANULADA','CASTIGADA') "
                                + "THEN 'PARCIAL' ELSE estado END, "
                                + "fecha_cierre = NULL, "
                                + "observacion = COALESCE(observacion,'') || :obs, "
                                + "fecha_actualizacion = CURRENT_TIMESTAMP "
                                + "WHERE id = :id")
                .setParameter("faltante", faltante)
                .setParameter("obs", " · Faltante QR HRE#" + historialElectronicoId + " +" + faltante)
                .setParameter("id", cxcId)
                .executeUpdate();
        log.info("CxC #{} saldo += {} por faltante QR hre={}", cxcId, faltante, historialElectronicoId);
        return cxcId;
    }

    private Integer findOrigenFondosIdByMetodoPago(Long metodoPagoId) {
        if (metodoPagoId == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "SELECT id FROM origen_fondos WHERE metodo_pago_id = :mp "
                                + "AND parent_origen_fondos_id IS NULL AND COALESCE(activo, TRUE) = TRUE "
                                + "ORDER BY id LIMIT 1")
                .setParameter("mp", metodoPagoId)
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            @SuppressWarnings("unchecked")
            List<Object> any = entityManager.createNativeQuery(
                            "SELECT id FROM origen_fondos WHERE metodo_pago_id = :mp "
                                    + "AND COALESCE(activo, TRUE) = TRUE ORDER BY id LIMIT 1")
                    .setParameter("mp", metodoPagoId)
                    .getResultList();
            if (any.isEmpty() || any.get(0) == null) {
                return null;
            }
            return ((Number) any.get(0)).intValue();
        }
        return ((Number) rows.get(0)).intValue();
    }

    private boolean tieneMovimientoPorIdentificar(Long notifId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM movimiento_origen_fondos "
                                + "WHERE id_referencia = :idRef "
                                + "AND origen_tipo = :tipo")
                .setParameter("idRef", notifId)
                .setParameter("tipo", ORIGEN_TIPO_DEFAULT)
                .getSingleResult();
        return n != null && n.longValue() > 0;
    }

    /**
     * Bolsa donde quedó el dinero tras el auto-traslado de plantilla
     * (destino de EGRESO LULO → «Para ordenar», o cuenta con impacto +).
     */
    private Integer resolverBolsaPorIdentificar(
            NotificacionEmailPago notif,
            PlantillaNotificacionPago plantilla
    ) {
        if (plantilla != null && plantilla.getOrigenFondosDestinoId() != null) {
            return plantilla.getOrigenFondosDestinoId();
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "SELECT origen_fondos_id FROM movimiento_origen_fondos "
                                + "WHERE id_referencia = :idRef AND origen_tipo = :tipo "
                                + "AND impacto > 0 ORDER BY id DESC LIMIT 1")
                .setParameter("idRef", notif.getId())
                .setParameter("tipo", ORIGEN_TIPO_DEFAULT)
                .getResultList();
        if (!rows.isEmpty() && rows.get(0) != null) {
            return ((Number) rows.get(0)).intValue();
        }
        Integer bolsaSinClasificar = firstNonNull(
                findOrigenFondosIdByNombre("Sin Clasificar"),
                findOrigenFondosIdByNombre("Para ordenar"));
        if (bolsaSinClasificar != null) {
            return bolsaSinClasificar;
        }
        return null;
    }

    private Integer resolverDestinoPorClasificacion(String clasificacion) {
        if (clasificacion == null) {
            return null;
        }
        switch (clasificacion.trim().toUpperCase()) {
            case "CUENTA_PERSONAL":
                return firstNonNull(
                        findOrigenFondosIdByNombre("Cuenta del dueño"),
                        findOrigenFondosIdByNombre("Personal administrador"),
                        findOrigenFondosIdByNombreLike("%dueño%"),
                        findOrigenFondosIdByNombreLike("%personal%admin%"));
            case "ANTICIPO_SALARIO":
                return firstNonNull(
                        findOrigenFondosIdByNombre("Bolsillo Nómina"),
                        findOrigenFondosIdByNombreLike("%nómina%"),
                        findOrigenFondosIdByNombreLike("%nomina%"));
            case "VALE_EMPLEADO":
                return firstNonNull(
                        findOrigenFondosIdByNombre("Bolsillo Nómina"),
                        findOrigenFondosIdByNombre("Cuenta del dueño"),
                        findOrigenFondosIdByNombre("Personal administrador"),
                        findOrigenFondosIdByNombreLike("%nómina%"),
                        findOrigenFondosIdByNombreLike("%nomina%"));
            case "GASTO_NEGOCIO":
            case "OTRO_LEGALIZADO":
            default:
                return null;
        }
    }

    private Integer motivoIdPorClasificacion(String clasificacion) {
        if (clasificacion == null) {
            return null;
        }
        String codigo;
        switch (clasificacion.trim().toUpperCase()) {
            case "VALE_EMPLEADO":
                codigo = "LEGALIZAR_VALE_EMPLEADO";
                break;
            case "ANTICIPO_SALARIO":
                codigo = "LEGALIZAR_ANTICIPO_SALARIO";
                break;
            case "CUENTA_PERSONAL":
                codigo = "LEGALIZAR_CUENTA_PERSONAL";
                break;
            case "GASTO_NEGOCIO":
                codigo = "LEGALIZAR_GASTO_NEGOCIO";
                break;
            default:
                return null;
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "SELECT id FROM motivo_movimiento WHERE codigo = :codigo LIMIT 1")
                .setParameter("codigo", codigo)
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return ((Number) rows.get(0)).intValue();
    }

    private Integer findOrigenFondosIdByNombre(String nombreExacto) {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "SELECT id FROM origen_fondos WHERE LOWER(nombre) = LOWER(:n) "
                                + "AND COALESCE(activo, TRUE) = TRUE ORDER BY id LIMIT 1")
                .setParameter("n", nombreExacto)
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return ((Number) rows.get(0)).intValue();
    }

    private Integer findOrigenFondosIdByNombreLike(String pattern) {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "SELECT id FROM origen_fondos WHERE LOWER(nombre) LIKE LOWER(:p) "
                                + "AND COALESCE(activo, TRUE) = TRUE ORDER BY id LIMIT 1")
                .setParameter("p", pattern)
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return ((Number) rows.get(0)).intValue();
    }

    @SafeVarargs
    private static Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private void insertarTraslado(
            Integer origenId,
            Integer destinoId,
            BigDecimal valor,
            LocalDate fecha,
            String usuarioId,
            String tercero,
            String observacion,
            String origenTipo,
            Long notifId,
            Integer motivoId
    ) {
        insertarTraslado(origenId, destinoId, valor, fecha, usuarioId, tercero, observacion,
                origenTipo, notifId, motivoId, null);
    }

    private void insertarTraslado(
            Integer origenId,
            Integer destinoId,
            BigDecimal valor,
            LocalDate fecha,
            String usuarioId,
            String tercero,
            String observacion,
            String origenTipo,
            Long notifId,
            Integer motivoId,
            String clasificacionOperativa
    ) {
        String grupoId = UUID.randomUUID().toString();
        insertarFila(origenId, destinoId, "TRASLADO", valor, valor.negate(), fecha, usuarioId,
                tercero, observacion, origenTipo, notifId, grupoId, motivoId, clasificacionOperativa);
        insertarFila(destinoId, null, "TRASLADO", valor, valor, fecha, usuarioId,
                tercero, observacion, origenTipo, notifId, grupoId, motivoId, clasificacionOperativa);
        log.info("BD movimiento TRASLADO notif={} origenOf={} destinoOf={} valor={} origenTipo={} clasificacion={}",
                notifId, origenId, destinoId, valor, origenTipo, clasificacionOperativa);
    }

    private void insertarSimple(
            Integer cuentaId,
            String tipo,
            BigDecimal valor,
            BigDecimal impacto,
            LocalDate fecha,
            String usuarioId,
            String tercero,
            String observacion,
            String origenTipo,
            Long notifId,
            Integer motivoId
    ) {
        insertarFila(cuentaId, null, tipo, valor, impacto, fecha, usuarioId, tercero, observacion,
                origenTipo, notifId, null, motivoId, null);
        log.info("BD movimiento {} notif={} of={} valor={}", tipo, notifId, cuentaId, valor);
    }

    private void insertarFila(
            Integer cuentaId,
            Integer destinoId,
            String tipo,
            BigDecimal valor,
            BigDecimal impacto,
            LocalDate fecha,
            String usuarioId,
            String tercero,
            String observacion,
            String origenTipo,
            Long notifId,
            String grupoId,
            Integer motivoId,
            String clasificacionOperativa
    ) {
        if (!cuentaExiste(cuentaId)) {
            throw new IllegalArgumentException("origen de fondos no existe: " + cuentaId);
        }
        BigDecimal saldoAntes = saldoDe(cuentaId);
        BigDecimal saldoDespues = saldoAntes.add(impacto);
        Long metodoPagoId = metodoPagoDe(cuentaId);

        NativeQuery<?> q = entityManager.createNativeQuery(
                        "INSERT INTO movimiento_origen_fondos ("
                                + "fecha, usuario_id, origen_fondos_id, origen_destino_id, tipo_movimiento, "
                                + "valor, impacto, saldo_antes, saldo_despues, metodo_pago_id, tercero_nombre, "
                                + "motivo_movimiento_id, observacion, origen_tipo, id_referencia, grupo_traslado_id, "
                                + "clasificacion_operativa"
                                + ") VALUES ("
                                + ":fecha, :usuarioId, :cuentaId, :destinoId, :tipo, "
                                + ":valor, :impacto, :saldoAntes, :saldoDespues, :metodoPagoId, :tercero, "
                                + ":motivoId, :observacion, :origenTipo, :idRef, :grupoId, :clasificacion)")
                .unwrap(NativeQuery.class);
        q.setParameter("fecha", Date.valueOf(fecha), StandardBasicTypes.DATE);
        q.setParameter("usuarioId", usuarioId, StandardBasicTypes.STRING);
        q.setParameter("cuentaId", cuentaId, StandardBasicTypes.INTEGER);
        q.setParameter("destinoId", destinoId, StandardBasicTypes.INTEGER);
        q.setParameter("tipo", tipo, StandardBasicTypes.STRING);
        q.setParameter("valor", valor, StandardBasicTypes.BIG_DECIMAL);
        q.setParameter("impacto", impacto, StandardBasicTypes.BIG_DECIMAL);
        q.setParameter("saldoAntes", saldoAntes, StandardBasicTypes.BIG_DECIMAL);
        q.setParameter("saldoDespues", saldoDespues, StandardBasicTypes.BIG_DECIMAL);
        q.setParameter("metodoPagoId", metodoPagoId, StandardBasicTypes.LONG);
        q.setParameter("tercero", tercero, StandardBasicTypes.STRING);
        q.setParameter("motivoId", motivoId, StandardBasicTypes.INTEGER);
        q.setParameter("observacion", observacion, StandardBasicTypes.STRING);
        q.setParameter("origenTipo", origenTipo, StandardBasicTypes.STRING);
        q.setParameter("idRef", notifId, StandardBasicTypes.LONG);
        q.setParameter("grupoId", grupoId, StandardBasicTypes.STRING);
        q.setParameter("clasificacion", clasificacionOperativa, StandardBasicTypes.STRING);
        q.executeUpdate();
    }

    private boolean yaExiste(String origenTipo, Long notifId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM movimiento_origen_fondos "
                                + "WHERE origen_tipo = :tipo AND id_referencia = :idRef")
                .setParameter("tipo", origenTipo)
                .setParameter("idRef", notifId)
                .getSingleResult();
        return n != null && n.longValue() > 0;
    }

    private boolean cuentaExiste(Integer id) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM origen_fondos WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        return n != null && n.longValue() > 0;
    }

    private BigDecimal saldoDe(Integer cuentaId) {
        Object raw = entityManager.createNativeQuery(
                        "SELECT COALESCE(SUM(impacto), 0) FROM movimiento_origen_fondos WHERE origen_fondos_id = :id")
                .setParameter("id", cuentaId)
                .getSingleResult();
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal) {
            return (BigDecimal) raw;
        }
        return new BigDecimal(raw.toString());
    }

    private Long metodoPagoDe(Integer cuentaId) {
        Object raw = entityManager.createNativeQuery(
                        "SELECT metodo_pago_id FROM origen_fondos WHERE id = :id")
                .setParameter("id", cuentaId)
                .getSingleResult();
        if (raw == null) {
            return null;
        }
        return ((Number) raw).longValue();
    }

    private String resolverUsuarioSistema() {
        @SuppressWarnings("unchecked")
        List<Object> invitados = entityManager.createNativeQuery(
                        "SELECT CAST(id AS varchar) FROM security.usuario WHERE LOWER(nombre) LIKE '%invitado%' LIMIT 1")
                .getResultList();
        if (!invitados.isEmpty() && invitados.get(0) != null) {
            return invitados.get(0).toString();
        }
        @SuppressWarnings("unchecked")
        List<Object> any = entityManager.createNativeQuery(
                        "SELECT CAST(id AS varchar) FROM security.usuario ORDER BY id LIMIT 1")
                .getResultList();
        if (any.isEmpty() || any.get(0) == null) {
            throw new IllegalStateException("No hay usuario en security.usuario para el movimiento");
        }
        return any.get(0).toString();
    }

    private static String resolverNaturaleza(PlantillaNotificacionPago p) {
        if (p.getNaturaleza() != null && !p.getNaturaleza().isBlank()) {
            return p.getNaturaleza().trim().toUpperCase();
        }
        String nombre = p.getNombre() != null ? p.getNombre().toUpperCase() : "";
        if (nombre.contains("INGRESO")) {
            return "INGRESO";
        }
        return "EGRESO";
    }

    private static String blankToDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static String trunc(String v, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
