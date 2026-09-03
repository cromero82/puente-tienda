-- Visibilidad del panel flotante «Pagos electrónicos» en Tickets (solo UI).
-- true  = panel visible (default)
-- false = panel oculto; inbound/match automático en puente-tienda sigue igual
INSERT INTO configuracion_app (key, value)
SELECT 'notificaciones.activa', 'true'
WHERE NOT EXISTS (
    SELECT 1 FROM configuracion_app WHERE key = 'notificaciones.activa'
);