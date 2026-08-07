package com.infinitesoft.puente_tienda.entities;

import lombok.*;

import javax.persistence.*;

@Entity
@Table(name = "establecimiento")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Establecimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "referencia_cuenta_qr", length = 4)
    private String referenciaCuentaQr;

    @Column(name = "email_alerta_pagos", length = 255)
    private String emailAlertaPagos;

    private Boolean activo;
}
