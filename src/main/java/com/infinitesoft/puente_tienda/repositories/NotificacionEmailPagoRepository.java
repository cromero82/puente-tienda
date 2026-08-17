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

    List<NotificacionEmailPago> findByMontoAndEstadoVista(BigDecimal monto, String estadoVista);

    List<NotificacionEmailPago> findAllByOrderByRecibidoEnDesc();

    List<NotificacionEmailPago> findByPlantillaNotificacionIdOrderByIdAsc(Long plantillaNotificacionId);

    @Query("SELECT n FROM NotificacionEmailPago n WHERE "
            + "(:estadoVista IS NULL OR n.estadoVista = :estadoVista) AND "
            + "(:q IS NULL OR :q = '' OR LOWER(COALESCE(n.asunto, '')) LIKE LOWER(CONCAT('%', :q, '%')) "
            + " OR LOWER(COALESCE(n.cuerpoTexto, '')) LIKE LOWER(CONCAT('%', :q, '%')) "
            + " OR LOWER(COALESCE(n.nombrePagador, '')) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "ORDER BY n.recibidoEn DESC")
    List<NotificacionEmailPago> search(
            @Param("estadoVista") String estadoVista,
            @Param("q") String q);
}
