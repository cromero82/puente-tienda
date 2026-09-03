package com.infinitesoft.puente_tienda.repositories;

import com.infinitesoft.puente_tienda.entities.MetodoPago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetodoPagoRepository extends JpaRepository<MetodoPago, Long> {
    List<MetodoPago> findByPlantillaNotificacionPagoIsNotNull();

    List<MetodoPago> findByPermiteNotificacionTrueOrderByIdAsc();
}
