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
}
