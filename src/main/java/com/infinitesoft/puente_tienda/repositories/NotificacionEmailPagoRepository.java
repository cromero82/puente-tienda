package com.infinitesoft.puente_tienda.repositories;

import com.infinitesoft.puente_tienda.entities.NotificacionEmailPago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificacionEmailPagoRepository extends JpaRepository<NotificacionEmailPago, Long> {

    Optional<NotificacionEmailPago> findByMessageId(String messageId);

    List<NotificacionEmailPago> findByEstadoVistaOrderByRecibidoEnDesc(String estadoVista);
}
