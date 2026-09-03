package com.infinitesoft.puente_tienda.dto;

import lombok.Data;

@Data
public class PlantillaNotificacionRequest {
    private String nombre;
    private String cuerpo;
    /** Legado; el icono visible viene de metodo_pago.file. */
    private String icono;
    private Long metodoPagoId;
    private Boolean activo;
    private Integer orden;
    /** INGRESO | EGRESO */
    private String naturaleza;
    private Integer origenFondosOrigenId;
    private Integer origenFondosDestinoId;
    private String origenTipo;
}
