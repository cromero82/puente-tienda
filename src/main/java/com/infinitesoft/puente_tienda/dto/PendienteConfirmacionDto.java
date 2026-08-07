package com.infinitesoft.puente_tienda.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PendienteConfirmacionDto {
    private Long id;
    private Long historialReciboId;
    private Long sesionId;
    private Long metodoPagoId;
    private BigDecimal montoEsperado;
    private String estado;
    private String nombrePagador;
    private LocalDateTime fechaCreacion;
    private LocalDateTime fechaConfirmacion;
    /** Si hay varias CREADA con mismo monto, FE muestra ambigüedad. */
    private Boolean ambiguo;
    private List<CandidatoAmbiguoDto> candidatos;
}
