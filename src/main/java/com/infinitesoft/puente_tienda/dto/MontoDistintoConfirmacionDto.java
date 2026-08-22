package com.infinitesoft.puente_tienda.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MontoDistintoConfirmacionDto {
    private String code;
    private Long historialElectronicoId;
    private Long notificacionId;
    private BigDecimal montoEsperado;
    private BigDecimal montoRecibido;
    private BigDecimal diferencia;
    private String nombrePagador;
    private String mensaje;
    /** true si recibido &gt; esperado → cajero elige OF de la devolución en efectivo. */
    private Boolean requiereOrigenDevolucion;
    /** true si recibido &lt; esperado → se abrirá/ajustará CxC por el faltante. */
    private Boolean creaCxcFaltante;
}
