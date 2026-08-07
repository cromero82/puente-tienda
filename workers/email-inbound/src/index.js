/**
 * Cloudflare Email Worker: Email Routing → POST /api/email-inbound (POS).
 * Regla: pagos@mayaksoluciones.com → este Worker.
 */
export default {
  async email(message, env, ctx) {
    const inboundUrl = env.INBOUND_URL;
    const storeKey = env.STORE_KEY;
    if (!inboundUrl || !storeKey) {
      message.setReject('Worker mal configurado (INBOUND_URL / STORE_KEY)');
      return;
    }

    let raw = '';
    try {
      raw = await new Response(message.raw).text();
    } catch (e) {
      message.setReject('No se pudo leer el correo');
      return;
    }

    const subject = message.headers.get('subject') || '';
    const messageId =
      message.headers.get('message-id') ||
      message.headers.get('Message-ID') ||
      `cf-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;

    const text = extractTextPlain(raw) || raw;

    const payload = {
      messageId: String(messageId).trim(),
      from: message.from,
      to: message.to,
      subject,
      text,
      html: extractHtml(raw) || undefined
    };

    const res = await fetch(inboundUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Store-Key': storeKey
      },
      body: JSON.stringify(payload)
    });

    if (!res.ok) {
      const body = await res.text().catch(() => '');
      console.error('inbound failed', res.status, body.slice(0, 300));
      message.setReject(`POS inbound ${res.status}`);
    }
  }
};

function extractTextPlain(raw) {
  if (!raw) return '';
  const m = raw.match(
    /Content-Type:\s*text\/plain[\s\S]*?\r?\n\r?\n([\s\S]*?)(?=\r?\n--|\r?\nContent-Type:|$)/i
  );
  if (m) {
    return decodeQuotedPrintable(m[1]).trim();
  }
  const parts = raw.split(/\r?\n\r?\n/);
  if (parts.length > 1 && !/multipart\//i.test(raw.slice(0, 500))) {
    return decodeQuotedPrintable(parts.slice(1).join('\n\n')).trim();
  }
  return '';
}

function extractHtml(raw) {
  if (!raw) return '';
  const m = raw.match(
    /Content-Type:\s*text\/html[\s\S]*?\r?\n\r?\n([\s\S]*?)(?=\r?\n--|\r?\nContent-Type:|$)/i
  );
  return m ? decodeQuotedPrintable(m[1]).trim() : '';
}

function decodeQuotedPrintable(s) {
  return s
    .replace(/=\r?\n/g, '')
    .replace(/=([0-9A-Fa-f]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
}
