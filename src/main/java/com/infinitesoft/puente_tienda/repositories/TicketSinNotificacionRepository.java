package com.infinitesoft.puente_tienda.repositories;

import com.infinitesoft.puente_tienda.entities.TicketSinNotificacion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicketSinNotificacionRepository extends JpaRepository<TicketSinNotificacion, Long> {

    Optional<TicketSinNotificacion> findByHistorialReciboId(Long historialReciboId);

    List<TicketSinNotificacion> findAllByOrderByMarcadoEnDesc();
}
