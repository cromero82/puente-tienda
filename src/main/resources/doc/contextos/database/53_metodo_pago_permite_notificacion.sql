-- Métodos de pago con notificaciones electrónicas + vínculo plantilla → método
-- BD: controlneg_rmx_db (compartida relational + puente-tienda)

ALTER TABLE metodo_pago
    ADD COLUMN IF NOT EXISTS permite_notificacion BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN metodo_pago.permite_notificacion IS
    'Si true, pagos con este medio crean pendiente de confirmación por email y pueden usarse en plantillas de extracción.';

-- Bancolombia QR (id=2 en seeds típicos; fallback por sigla/descripción)
UPDATE metodo_pago
SET permite_notificacion = TRUE
WHERE id = 2
   OR UPPER(TRIM(COALESCE(sigla, ''))) = 'QR'
   OR UPPER(COALESCE(descripcion, '')) LIKE '%BANCOLOMBIA%';

ALTER TABLE plantilla_notificacion_pago
    ADD COLUMN IF NOT EXISTS metodo_pago_id BIGINT NULL
        REFERENCES metodo_pago (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_pnp_metodo_pago
    ON plantilla_notificacion_pago (metodo_pago_id);

COMMENT ON COLUMN plantilla_notificacion_pago.metodo_pago_id IS
    'Medio de pago al que aplica la plantilla; el icono se toma de metodo_pago.file.';

-- Backfill: plantilla QR → primer método con permite_notificacion + INGRESO
UPDATE plantilla_notificacion_pago p
SET metodo_pago_id = COALESCE(
        p.metodo_pago_id,
        (
            SELECT mp.id
            FROM metodo_pago mp
            WHERE mp.permite_notificacion = TRUE
            ORDER BY mp.id
            LIMIT 1
        )
    ),
    naturaleza = COALESCE(NULLIF(TRIM(p.naturaleza), ''), 'INGRESO')
WHERE p.metodo_pago_id IS NULL
  AND UPPER(TRIM(p.nombre)) IN ('QR', 'BREVE');

UPDATE plantilla_notificacion_pago
SET naturaleza = 'INGRESO'
WHERE (naturaleza IS NULL OR TRIM(naturaleza) = '')
  AND metodo_pago_id IS NOT NULL;

-- icono deja de ser obligatorio (sigue como legado / denormalizado en notificaciones)
ALTER TABLE plantilla_notificacion_pago
    ALTER COLUMN icono DROP NOT NULL;
