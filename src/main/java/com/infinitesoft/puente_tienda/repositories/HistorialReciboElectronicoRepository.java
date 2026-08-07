package com.infinitesoft.puente_tienda.repositories;

import com.infinitesoft.puente_tienda.entities.HistorialReciboElectronico;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;

public interface HistorialReciboElectronicoRepository
        extends JpaRepository<HistorialReciboElectronico, Long> {

    List<HistorialReciboElectronico> findByEstadoAndSesionIdAndMontoEsperado(
            String estado, Long sesionId, BigDecimal montoEsperado);

    List<HistorialReciboElectronico> findByEstadoAndSesionIdOrderByFechaCreacionAsc(
            String estado, Long sesionId);

    List<HistorialReciboElectronico> findByEstadoAndMontoEsperado(String estado, BigDecimal montoEsperado);
}
