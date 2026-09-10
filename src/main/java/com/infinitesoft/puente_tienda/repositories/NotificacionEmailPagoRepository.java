package com.infinitesoft.puente_tienda.repositories;

import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface NotificacionEmailPagoRepository extends JpaRepository<NotificacionEmailPago, Long> {

    Optional<NotificacionEmailPago> findByMessageId(String messageId);

    List<NotificacionEmailPago> findByEstadoVistaOrderByRecibidoEnDesc(String estadoVista);

    Optional<NotificacionEmailPago> findFirstByHistorialReciboElectronicoIdOrderByRecibidoEnDesc(Long historialReciboElectronicoId);

    Optional<NotificacionEmailPago> findFirstByEgresoId(Long egresoId);

    List<NotificacionEmailPago> findByMontoAndEstadoVista(BigDecimal monto, String estadoVista);

    List<NotificacionEmailPago> findAllByOrderByRecibidoEnDesc();

    List<NotificacionEmailPago> findByPlantillaNotificacionIdOrderByIdAsc(Long plantillaNotificacionId);

    /** Notificaciones aún sin plantilla (candidatas a rematch al guardar plantilla). */
    @Query("SELECT n FROM NotificacionEmailPago n WHERE "
            + "n.plantillaNotificacionId IS NULL "
            + "AND UPPER(COALESCE(n.estadoVista, '')) <> 'ARCHIVADA' "
            + "ORDER BY n.id ASC")
    List<NotificacionEmailPago> findSinPlantillaNoArchivadas();

    @Query("SELECT n FROM NotificacionEmailPago n WHERE "
            + "(:estadoVista IS NULL OR n.estadoVista = :estadoVista) AND "
            + "(:q IS NULL OR :q = '' OR LOWER(COALESCE(n.asunto, '')) LIKE LOWER(CONCAT('%', :q, '%')) "
            + " OR LOWER(COALESCE(n.cuerpoTexto, '')) LIKE LOWER(CONCAT('%', :q, '%')) "
            + " OR LOWER(COALESCE(n.nombrePagador, '')) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "ORDER BY n.recibidoEn DESC")
    List<NotificacionEmailPago> search(
            @Param("estadoVista") String estadoVista,
            @Param("q") String q);

    /**
     * Cola por identificar: sin legalizar, sin vincular a ticket/egreso y no archivadas.
     */
    @Query("SELECT n FROM NotificacionEmailPago n WHERE "
            + "n.clasificacion IS NULL AND UPPER(n.estadoVista) <> 'ARCHIVADA' "
            + "AND n.egresoId IS NULL "
            + "AND n.historialReciboElectronicoId IS NULL "
            + "AND (n.vinculoOperacion IS NULL OR UPPER(n.vinculoOperacion) <> 'ASOCIADA') AND "
            + "(:q IS NULL OR :q = '' OR LOWER(COALESCE(n.asunto, '')) LIKE LOWER(CONCAT('%', :q, '%')) "
            + " OR LOWER(COALESCE(n.cuerpoTexto, '')) LIKE LOWER(CONCAT('%', :q, '%')) "
            + " OR LOWER(COALESCE(n.nombrePagador, '')) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "ORDER BY n.recibidoEn DESC")
    List<NotificacionEmailPago> searchPorIdentificar(@Param("q") String q);

    /**
     * Candidatas a asociar a un egreso ya registrado (correo tardío).
     * Plantilla EGRESO (o legado sin naturaleza), vínculo PENDIENTE, sin operación.
     */
    @Query(value = "SELECT n.* FROM notificacion_email_pago n "
            + "LEFT JOIN plantilla_notificacion_pago p ON p.id = n.plantilla_notificacion_id "
            + "WHERE n.vinculo_operacion = 'PENDIENTE' "
            + "AND n.egreso_id IS NULL "
            + "AND n.historial_recibo_electronico_id IS NULL "
            + "AND n.clasificacion IS NULL "
            + "AND UPPER(COALESCE(n.estado_vista, '')) <> 'ARCHIVADA' "
            + "AND n.plantilla_notificacion_id IS NOT NULL "
            + "AND (UPPER(BTRIM(COALESCE(p.naturaleza, ''))) = 'EGRESO' "
            + "     OR BTRIM(COALESCE(p.naturaleza, '')) = '') "
            + "AND CAST(n.recibido_en AS date) BETWEEN :desde AND :hasta "
            + "ORDER BY ABS(COALESCE(n.monto, 0) - :monto) ASC, n.recibido_en DESC "
            + "LIMIT 30",
            nativeQuery = true)
    List<NotificacionEmailPago> findCandidatasEgreso(
            @Param("monto") java.math.BigDecimal monto,
            @Param("desde") java.time.LocalDate desde,
            @Param("hasta") java.time.LocalDate hasta);

    /**
     * Correos EGRESO aún sin operación y sin par POR IDENTIFICAR (bandeja).
     */
    @Query(value = "SELECT n.* FROM notificacion_email_pago n "
            + "JOIN plantilla_notificacion_pago p ON p.id = n.plantilla_notificacion_id "
            + "WHERE n.vinculo_operacion = 'PENDIENTE' "
            + "AND n.egreso_id IS NULL "
            + "AND n.historial_recibo_electronico_id IS NULL "
            + "AND n.clasificacion IS NULL "
            + "AND UPPER(COALESCE(n.estado_vista, '')) <> 'ARCHIVADA' "
            + "AND (UPPER(BTRIM(COALESCE(p.naturaleza, ''))) = 'EGRESO' "
            + "     OR BTRIM(COALESCE(p.naturaleza, '')) = '') "
            + "AND NOT EXISTS ( "
            + "    SELECT 1 FROM movimiento_origen_fondos m "
            + "    WHERE m.id_referencia = n.id "
            + "    AND m.origen_tipo = 'MOVIMIENTO BANCO POR IDENTIFICAR' "
            + ") "
            + "ORDER BY n.recibido_en DESC "
            + "LIMIT 50",
            nativeQuery = true)
    List<NotificacionEmailPago> findPendientesEgresoSinMovimiento();
}
