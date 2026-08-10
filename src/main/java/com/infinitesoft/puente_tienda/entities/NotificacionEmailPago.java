package com.infinitesoft.puente_tienda.entities;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "notificacion_email_pago")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificacionEmailPago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", length = 255)
    private String messageId;

    @Column(name = "recibido_en", nullable = false)
    private LocalDateTime recibidoEn;

    @Column(length = 500)
    private String asunto;

    @Column(name = "cuerpo_raw", columnDefinition = "TEXT")
    private String cuerpoRaw;

    @Column(name = "cuerpo_texto", columnDefinition = "TEXT")
    private String cuerpoTexto;

    @Column(precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(name = "nombre_pagador", length = 200)
    private String nombrePagador;

    @Column(name = "referencia_cuenta", length = 4)
    private String referenciaCuenta;

    @Column(name = "metodo_pago_id")
    private Long metodoPagoId;

    @Column(name = "estado_vista", nullable = false, length = 20)
    @Builder.Default
    private String estadoVista = "PENDIENTE";

    @Column(name = "historial_recibo_electronico_id")
    private Long historialReciboElectronicoId;

    @Column(name = "plantilla_notificacion_id")
    private Long plantillaNotificacionId;

    @Column(name = "plantilla_nombre", length = 40)
    private String plantillaNombre;

    @Column(name = "plantilla_icono", length = 120)
    private String plantillaIcono;

    @PrePersist
    void onCreate() {
        if (recibidoEn == null) {
            recibidoEn = LocalDateTime.now();
        }
        if (estadoVista == null) {
            estadoVista = "PENDIENTE";
        }
    }
}
