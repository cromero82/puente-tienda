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
    /** Cliente del ticket (historial_recibo), si está identificado. */
    private String nombreCliente;
    private String numeroVenta;
    private LocalDateTime fechaCreacion;
    private LocalDateTime fechaConfirmacion;
    /** Notificación asociada (match o ambigüedad). */
    private Long notificacionId;
    private Boolean ambiguo;
    private List<CandidatoAmbiguoDto> candidatos;
}
