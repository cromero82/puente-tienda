package com.infinitesoft.puente_tienda.service;

import com.infinitesoft.puente_tienda.dto.FaltanteReaperturaResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;

/**
 * Faltante QR en venta formalizada → reabre ticket vivo (productos) para que el cajero
 * abra CxC con el modal «Generar crédito». No crea CxC ni movimiento OF (eso lo hace el abono).
 * No reintegra inventario (el producto ya salió).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaltanteQrCreditoService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Anula VTA, reabre ticket+recibo con el detalle, deja HRE ligado al historial
     * (se retargeta a abono al crear el crédito en FE).
     */
    @Transactional
    public FaltanteReaperturaResult reabrirTicketParaCreditoManual(
            BigDecimal faltante,
            BigDecimal montoRecibido,
            BigDecimal montoEsperado,
            Long historialReciboId,
            Long historialElectronicoId,
            Long metodoPagoId
    ) {
        if (historialReciboId == null || faltante == null || faltante.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (montoRecibido == null || montoRecibido.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("montoRecibido inválido para reabrir ticket");
        }

        @SuppressWarnings("unchecked")
        List<Object[]> hrRows = entityManager.createNativeQuery(
                        "SELECT id, cliente_id, total, sesion_id, metodo_pago_id "
                                + "FROM historial_recibo WHERE id = :id")
                .unwrap(NativeQuery.class)
                .addScalar("id", StandardBasicTypes.LONG)
                .addScalar("cliente_id", StandardBasicTypes.LONG)
                .addScalar("total", StandardBasicTypes.BIG_DECIMAL)
                .addScalar("sesion_id", StandardBasicTypes.LONG)
                .addScalar("metodo_pago_id", StandardBasicTypes.LONG)
                .setParameter("id", historialReciboId)
                .getResultList();
        if (hrRows.isEmpty() || hrRows.get(0) == null) {
            log.warn("historial_recibo #{} no encontrado para faltante→reabrir", historialReciboId);
            return null;
        }
        Object[] hr = hrRows.get(0);

        Long clienteId = hr[1] != null ? ((Number) hr[1]).longValue() : 1L;
        BigDecimal totalTicket = hr[2] instanceof BigDecimal
                ? (BigDecimal) hr[2]
                : (montoEsperado != null ? montoEsperado : faltante.add(montoRecibido));

        final Long sesionIdFinal;
        Long sesionTmp = null;
        @SuppressWarnings("unchecked")
        List<Long> sesionHre = entityManager.createNativeQuery(
                        "SELECT sesion_id FROM historial_recibos_electronicos WHERE id = :id")
                .unwrap(NativeQuery.class)
                .addScalar("sesion_id", StandardBasicTypes.LONG)
                .setParameter("id", historialElectronicoId)
                .getResultList();
        if (!sesionHre.isEmpty() && sesionHre.get(0) != null) {
            sesionTmp = sesionHre.get(0);
        } else if (hr[3] != null) {
            sesionTmp = ((Number) hr[3]).longValue();
        }
        if (sesionTmp == null) {
            throw new IllegalArgumentException(
                    "No hay sesión de caja para reabrir el ticket (faltante QR).");
        }
        sesionIdFinal = sesionTmp;

        final Long mpIdFinal = metodoPagoId != null
                ? metodoPagoId
                : (hr[4] != null ? ((Number) hr[4]).longValue() : 2L);

        // 1) Recibo vivo (abono QR ya recibido queda en monto_recibido)
        Long reciboId = insertReturningLong(
                "INSERT INTO recibo (cliente_id, estado_id, metodo_pago_id, sesion_id, total, monto_recibido) "
                        + "VALUES (:clienteId, 1, :mp, :sesionId, :total, :recibido) RETURNING id",
                q -> {
                    q.setParameter("clienteId", clienteId);
                    q.setParameter("mp", mpIdFinal);
                    q.setParameter("sesionId", sesionIdFinal);
                    q.setParameter("total", totalTicket);
                    q.setParameter("recibido", montoRecibido);
                });

        @SuppressWarnings("unchecked")
        List<Object[]> dets = entityManager.createNativeQuery(
                        "SELECT producto_id, cantidad, subtotal "
                                + "FROM historial_recibo_detalle WHERE recibo_id = :id")
                .unwrap(NativeQuery.class)
                .addScalar("producto_id", StandardBasicTypes.LONG)
                .addScalar("cantidad", StandardBasicTypes.INTEGER)
                .addScalar("subtotal", StandardBasicTypes.BIG_DECIMAL)
                .setParameter("id", historialReciboId)
                .getResultList();
        for (Object[] d : dets) {
            entityManager.createNativeQuery(
                            "INSERT INTO recibo_detalle (recibo_id, producto_id, cantidad, subtotal) "
                                    + "VALUES (:reciboId, :prod, :cant, :sub)")
                    .setParameter("reciboId", reciboId)
                    .setParameter("prod", d[0])
                    .setParameter("cant", d[1])
                    .setParameter("sub", d[2])
                    .executeUpdate();
        }

        // 2) Ticket con nombrado estándar Ticket N + orden
        long orden = siguienteOrden(sesionIdFinal);
        String ticketNombre = "Ticket " + orden;
        Long ticketId = insertReturningLong(
                "INSERT INTO ticket (sesion_id, nombre, orden) VALUES (:sesionId, :nombre, :orden) RETURNING id",
                q -> {
                    q.setParameter("sesionId", sesionIdFinal);
                    q.setParameter("nombre", ticketNombre);
                    q.setParameter("orden", orden);
                });

        entityManager.createNativeQuery(
                        "INSERT INTO ticket_recibo (ticket_id, recibo_id) VALUES (:t, :r)")
                .setParameter("t", ticketId)
                .setParameter("r", reciboId)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "UPDATE sesion SET ultimo_ticket_id = :t WHERE id = :s")
                .setParameter("t", ticketId)
                .setParameter("s", sesionIdFinal)
                .executeUpdate();

        // 3) Anular VTA / marcar historial (sin reintegro kardex)
        entityManager.createNativeQuery(
                        "UPDATE documento_venta SET estado = 'ANULADO' "
                                + "WHERE historial_recibo_id = :id AND estado = 'VIGENTE'")
                .setParameter("id", historialReciboId)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "UPDATE historial_recibo SET estado_id = 3 WHERE id = :id")
                .setParameter("id", historialReciboId)
                .executeUpdate();

        // HRE sigue ligado a historial_recibo (XOR); FE/abrir CxC lo retargeta al abono.

        log.info(
                "Faltante QR→ticket reabierto (CxC manual) ticket={} recibo={} faltante={} recibido={} sesion={}",
                ticketId, reciboId, faltante, montoRecibido, sesionIdFinal);

        return FaltanteReaperturaResult.builder()
                .ticketId(ticketId)
                .reciboId(reciboId)
                .sesionId(sesionIdFinal)
                .metodoPagoId(mpIdFinal)
                .historialElectronicoId(historialElectronicoId)
                .totalTicket(totalTicket)
                .montoRecibido(montoRecibido)
                .faltante(faltante)
                .build();
    }

    private long siguienteOrden(Long sesionId) {
        Object raw = entityManager.createNativeQuery(
                        "SELECT COALESCE(MAX(orden), 0) AS m FROM ticket WHERE sesion_id = :s")
                .unwrap(NativeQuery.class)
                .addScalar("m", StandardBasicTypes.LONG)
                .setParameter("s", sesionId)
                .getSingleResult();
        long max = raw != null ? ((Number) raw).longValue() : 0L;
        return max + 1;
    }

    private interface QueryBinder {
        void bind(javax.persistence.Query q);
    }

    private Long insertReturningLong(String sql, QueryBinder binder) {
        NativeQuery<?> q = entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
        q.addScalar("id", StandardBasicTypes.LONG);
        binder.bind(q);
        Object id = q.getSingleResult();
        return id != null ? ((Number) id).longValue() : null;
    }
}
