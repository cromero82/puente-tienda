package com.infinitesoft.puente_tienda.repositories;

import com.infinitesoft.puente_tienda.entities.Establecimiento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EstablecimientoRepository extends JpaRepository<Establecimiento, Long> {
    Optional<Establecimiento> findFirstByActivoTrueOrderByIdAsc();
}
