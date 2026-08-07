package com.infinitesoft.puente_tienda.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class EmailInboundRequest {
    private String messageId;
    private String from;
    private String to;
    private String subject;
    private String text;
    private String html;
    private Long metodoPagoId;
}
