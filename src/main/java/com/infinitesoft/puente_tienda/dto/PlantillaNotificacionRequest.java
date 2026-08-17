package com.infinitesoft.puente_tienda.dto;

import lombok.Data;

@Data
public class PlantillaNotificacionRequest {
    private String nombre;
    private String cuerpo;
    private String icono;
    private Boolean activo;
    private Integer orden;
    /** INGRESO | EGRESO */
    private String naturaleza;
    private Integer origenFondosOrigenId;
    private Integer origenFondosDestinoId;
    private String origenTipo;
}
