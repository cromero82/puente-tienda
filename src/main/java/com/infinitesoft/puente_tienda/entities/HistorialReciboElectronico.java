package com.infinitesoft.puente_tienda.entities;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "historial_recibos_electronicos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HistorialReciboElectronico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "historial_recibo_id", nullable = false)
    private Long historialReciboId;

    @Column(name = "sesion_id")
    private Long sesionId;

    @Column(name = "metodo_pago_id")
    private Long metodoPagoId;

    @Column(name = "monto_esperado", nullable = false, precision = 12, scale = 2)
    private BigDecimal montoEsperado;

    @Column(nullable = false, length = 20)
    private String estado;

    @Column(name = "nombre_pagador", length = 200)
    private String nombrePagador;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "fecha_confirmacion")
    private LocalDateTime fechaConfirmacion;
}
