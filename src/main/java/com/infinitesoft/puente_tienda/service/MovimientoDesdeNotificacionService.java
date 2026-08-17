package com.infinitesoft.puente_tienda.service;

import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import com.infinitesoft.puente_tienda.entities.PlantillaNotificacionPago;
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

    private final NotificacionEmailPagoRepository notificacionRepo;

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
                    origenTipo, notif.getId());
            return true;
        }

        Integer cuentaId = origenId != null ? origenId : destinoId;
        if ("INGRESO".equals(naturaleza)) {
            insertarSimple(cuentaId, "ENTRADA_MANUAL", valor, valor, fecha, usuarioId, tercero,
                    observacion, origenTipo, notif.getId());
        } else {
            insertarSimple(cuentaId, "SALIDA_EGRESO", valor, valor.negate(), fecha, usuarioId, tercero,
                    observacion, origenTipo, notif.getId());
        }
        return true;
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
            Long notifId
    ) {
        String grupoId = UUID.randomUUID().toString();
        insertarFila(origenId, destinoId, "TRASLADO", valor, valor.negate(), fecha, usuarioId,
                tercero, observacion, origenTipo, notifId, grupoId);
        insertarFila(destinoId, null, "TRASLADO", valor, valor, fecha, usuarioId,
                tercero, observacion, origenTipo, notifId, grupoId);
        log.info("BD movimiento TRASLADO notif={} origenOf={} destinoOf={} valor={}",
                notifId, origenId, destinoId, valor);
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
            Long notifId
    ) {
        insertarFila(cuentaId, null, tipo, valor, impacto, fecha, usuarioId, tercero, observacion,
                origenTipo, notifId, null);
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
            String grupoId
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
                                + "observacion, origen_tipo, id_referencia, grupo_traslado_id"
                                + ") VALUES ("
                                + ":fecha, :usuarioId, :cuentaId, :destinoId, :tipo, "
                                + ":valor, :impacto, :saldoAntes, :saldoDespues, :metodoPagoId, :tercero, "
                                + ":observacion, :origenTipo, :idRef, :grupoId)")
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
        q.setParameter("observacion", observacion, StandardBasicTypes.STRING);
        q.setParameter("origenTipo", origenTipo, StandardBasicTypes.STRING);
        q.setParameter("idRef", notifId, StandardBasicTypes.LONG);
        q.setParameter("grupoId", grupoId, StandardBasicTypes.STRING);
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
