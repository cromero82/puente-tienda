package com.infinitesoft.puente_tienda.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class NotificacionSinAsignarDto {
    private Long id;
    private BigDecimal monto;
    private String nombrePagador;
    private String asunto;
    private LocalDateTime recibidoEn;
    private Long metodoPagoId;
    /** Siempre true en esta lista; spam sin plantilla no se incluye. */
    private Boolean provienePlantillaExtraccion;
}
