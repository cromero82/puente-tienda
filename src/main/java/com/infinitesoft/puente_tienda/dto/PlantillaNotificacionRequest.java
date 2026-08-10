package com.infinitesoft.puente_tienda.dto;

import lombok.Data;

@Data
public class PlantillaNotificacionRequest {
    private String nombre;
    private String cuerpo;
    private String icono;
    private Boolean activo;
    private Integer orden;
}
