package com.infinitesoft.puente_tienda.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CandidatoAmbiguoDto {
    private Long historialElectronicoId;
    private Long historialReciboId;
    private BigDecimal montoEsperado;
    private String nombrePagadorSugerido;
}
