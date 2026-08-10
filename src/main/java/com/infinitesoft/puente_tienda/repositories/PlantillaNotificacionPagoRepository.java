package com.infinitesoft.puente_tienda.repositories;

import com.infinitesoft.puente_tienda.entities.PlantillaNotificacionPago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlantillaNotificacionPagoRepository extends JpaRepository<PlantillaNotificacionPago, Long> {

    List<PlantillaNotificacionPago> findAllByOrderByOrdenAscIdAsc();

    List<PlantillaNotificacionPago> findByActivoTrueOrderByOrdenAscIdAsc();

    Optional<PlantillaNotificacionPago> findByNombreIgnoreCase(String nombre);

    boolean existsByNombreIgnoreCaseAndIdNot(String nombre, Long id);
}
