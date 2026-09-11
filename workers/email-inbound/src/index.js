/**
 * Cloudflare Email Worker: Email Routing → POST /api/email-inbound (POS).
 * Regla: pagos@mayaksoluciones.com → este Worker.
 *
 * Fan-out:
 *  - correos a *tienda-infinito* → solo https://tienda-infinito.mayaksoluciones.com
 *  - el resto (pagos@, pruebas) → DEV (cotiza) y SANDBOX (pos-sandbox).
 * Si al menos un destino responde OK, el correo se acepta.
 */
export default {
  async email(message, env, ctx) {
    const storeKey = env.STORE_KEY;
    const urls = collectInboundUrls(env, message.to);
    if (!storeKey || urls.length === 0) {
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

    const text = extractMimePart(raw, 'text/plain');
    const html = extractMimePart(raw, 'text/html');
    const extraido = textoPlanoPreview(text, html);

    console.log(
      'email-inbound recibido from=' +
        (message.from || '-') +
        ' to=' +
        (message.to || '-') +
        ' subject=' +
        subject.slice(0, 80) +
        ' textLen=' +
        (text ? text.length : 0) +
        ' htmlLen=' +
        (html ? html.length : 0) +
        ' destinos=' +
        urls.length
    );
    console.log('email-inbound texto extraído: ' + extraido);

    const payload = {
      messageId: String(messageId).trim(),
      from: message.from,
      to: message.to,
      subject,
      text: text || undefined,
      html: html || undefined
    };

    const results = await Promise.all(
      urls.map((url) => postInbound(url, storeKey, payload))
    );

    const okAny = results.some((r) => r.ok);
    for (const r of results) {
      if (r.ok) {
        console.log('email-inbound POST ok status=' + r.status + ' url=' + r.url);
      } else {
        console.error(
          'email-inbound POST fail status=' +
            r.status +
            ' url=' +
            r.url +
            ' body=' +
            r.body.slice(0, 300)
        );
      }
    }

    if (!okAny) {
      const detail = results.map((r) => r.status).join(',');
      message.setReject(`POS inbound falló en todos (${detail})`);
    }
  }
};

/** URLs únicas según destinatario. */
function collectInboundUrls(env, messageTo) {
  const seen = new Set();
  const out = [];
  const to = String(messageTo || '').toLowerCase();
  const keys = to.includes('tienda-infinito')
    ? ['INBOUND_URL_TIENDA']
    : ['INBOUND_URL', 'INBOUND_URL_SANDBOX'];
  for (const key of keys) {
    const u = String(env[key] || '').trim();
    if (!u || seen.has(u)) continue;
    seen.add(u);
    out.push(u);
  }
  return out;
}

async function postInbound(inboundUrl, storeKey, payload) {
  try {
    const res = await fetch(inboundUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Store-Key': storeKey
      },
      body: JSON.stringify(payload)
    });
    const body = res.ok ? '' : await res.text().catch(() => '');
    return { url: inboundUrl, ok: res.ok, status: res.status, body };
  } catch (e) {
    return {
      url: inboundUrl,
      ok: false,
      status: 0,
      body: String(e && e.message ? e.message : e)
    };
  }
}

function textoPlanoPreview(text, html) {
  const t = String(text || '').replace(/\s+/g, ' ').trim();
  if (t.length >= 20) {
    return t.slice(0, 2000);
  }
  const fromHtml = String(html || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<\/p>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const out = t || fromHtml || '-';
  return out.slice(0, 2000);
}

function extractMimePart(raw, mimeType) {
  if (!raw) return '';
  const escaped = mimeType.replace('/', '\\/');
  const re = new RegExp(
    `Content-Type:\\s*${escaped}[^\\n]*([\\s\\S]*?)\\r?\\n\\r?\\n([\\s\\S]*?)(?=\\r?\\n--[-=\\w]+|\\r?\\nContent-Type:|$)`,
    'i'
  );
  const m = raw.match(re);
  if (!m) return '';
  const headers = `${m[0].slice(0, m[0].indexOf(m[2]))}${m[1] || ''}`;
  const body = m[2] || '';
  const charset = extractCharset(headers) || 'utf-8';
  if (/Content-Transfer-Encoding:\s*base64/i.test(headers)) {
    try {
      const bin = atob(body.replace(/\s+/g, ''));
      const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
      return new TextDecoder(normalizeCharset(charset), { fatal: false }).decode(bytes).trim();
    } catch (e) {
      return body.trim();
    }
  }
  return decodeQuotedPrintable(body, charset).trim();
}

function extractCharset(s) {
  const m = String(s || '').match(/charset\s*=\s*"?([^";\s]+)"?/i);
  return m ? m[1] : null;
}

function normalizeCharset(cs) {
  const c = (cs || 'utf-8').toLowerCase();
  if (c === 'utf8' || c === 'utf-8') return 'utf-8';
  if (c.includes('8859-1') || c === 'latin1' || c === 'iso-8859-1') return 'iso-8859-1';
  if (c.includes('1252')) return 'windows-1252';
  return 'utf-8';
}

/** QP → bytes → charset (UTF-8 por defecto). Evita mojibake Ã© / Â¡. */
function decodeQuotedPrintable(s, charset) {
  const withoutSoft = String(s || '').replace(/=\r?\n/g, '');
  const bytes = [];
  for (let i = 0; i < withoutSoft.length; i++) {
    if (withoutSoft[i] === '=' && /^[0-9A-Fa-f]{2}/.test(withoutSoft.slice(i + 1, i + 3))) {
      bytes.push(parseInt(withoutSoft.slice(i + 1, i + 3), 16));
      i += 2;
    } else {
      bytes.push(withoutSoft.charCodeAt(i) & 0xff);
    }
  }
  try {
    return new TextDecoder(normalizeCharset(charset), { fatal: false }).decode(new Uint8Array(bytes));
  } catch (e) {
    return new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(bytes));
  }
}
