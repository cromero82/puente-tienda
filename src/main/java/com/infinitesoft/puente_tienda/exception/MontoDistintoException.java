package com.infinitesoft.puente_tienda.exception;

import com.infinitesoft.puente_tienda.dto.MontoDistintoConfirmacionDto;
import lombok.Getter;

@Getter
public class MontoDistintoException extends RuntimeException {
    private final MontoDistintoConfirmacionDto body;

    public MontoDistintoException(MontoDistintoConfirmacionDto body) {
        super(body != null ? body.getMensaje() : "Monto distinto al esperado");
        this.body = body;
    }
}
