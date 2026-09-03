-- Backfill: plantillas de confirmación de venta (medios electrónicos)
-- BD: controlneg_rmx_db

-- QR / BREVE / cualquier plantilla ya ligada a un método → INGRESO
UPDATE plantilla_notificacion_pago
SET naturaleza = 'INGRESO'
WHERE (naturaleza IS NULL OR TRIM(naturaleza) = '')
  AND (
      metodo_pago_id IS NOT NULL
      OR UPPER(TRIM(nombre)) IN ('QR', 'BREVE')
  );

-- Asegurar vínculo a método con notificaciones si aún falta
UPDATE plantilla_notificacion_pago p
SET metodo_pago_id = (
    SELECT mp.id
    FROM metodo_pago mp
    WHERE mp.permite_notificacion = TRUE
    ORDER BY mp.id
    LIMIT 1
)
WHERE p.metodo_pago_id IS NULL
  AND UPPER(TRIM(COALESCE(p.naturaleza, ''))) = 'INGRESO';

COMMENT ON COLUMN plantilla_notificacion_pago.naturaleza IS
    'INGRESO = confirmación de venta (requiere metodo_pago_id + permite_notificacion). EGRESO = movimiento ledger (OF origen/destino).';
