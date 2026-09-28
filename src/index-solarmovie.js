const UPSTREAM = "https://ww1.solarmovie2.com";
const PLAYER_UPSTREAM = "https://ployan.live";
const IMG_UPSTREAM = "https://img.icdn.my.id";

const DOMAINS = [
  "https://ww1.solarmovie2.com",
  "https://solarmovie2.com",
];

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Proxy player API too: /ployan/* -> https://ployan.live/*
    if (url.pathname.startsWith("/ployan/")) {
      const upstreamUrl = new URL(PLAYER_UPSTREAM + url.pathname.replace("/ployan", "") + url.search);
      const headers = new Headers(request.headers);
      headers.set("Host", new URL(PLAYER_UPSTREAM).host);
      headers.set("Referer", PLAYER_UPSTREAM + "/");
      headers.set("Origin", PLAYER_UPSTREAM);
      headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
      headers.set("Accept", "application/json,*/*");
      const response = await fetch(upstreamUrl.toString(), { method: request.method, headers, redirect: "follow" });
      const resHeaders = new Headers(response.headers);
      resHeaders.set("Access-Control-Allow-Origin", "*");
      return new Response(response.body, { status: response.status, headers: resHeaders });
    }

    const upstreamUrl = new URL(UPSTREAM);
    upstreamUrl.pathname = url.pathname;
    upstreamUrl.search = url.search;

    const headers = new Headers(request.headers);
    headers.set("Host", upstreamUrl.host);
    headers.set("Referer", UPSTREAM + "/");
    headers.set("Origin", UPSTREAM);
    headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");

    let init = {
      method: request.method,
      headers,
      redirect: "follow",
    };

    if (!["GET", "HEAD"].includes(request.method)) {
      init.body = request.body;
    }

    const response = await fetch(upstreamUrl.toString(), init);

    const resHeaders = new Headers(response.headers);
    resHeaders.set("Access-Control-Allow-Origin", "*");
    resHeaders.delete("content-security-policy");
    resHeaders.delete("content-security-policy-report-only");

    const contentType = resHeaders.get("content-type") || "";
    let body;

    if (contentType.includes("text/html")) {
      let text = await response.text();
      for (const domain of DOMAINS) {
        text = text.split(domain).join(url.origin);
      }
      text = text.replace(/href="\//g, `href="${url.origin}/`);
      text = text.replace(/src="\//g, `src="${url.origin}/`);
      text = text.replace(/action="\//g, `action="${url.origin}/`);
      text = text.replace(/srcset="\//g, `srcset="${url.origin}/`);
      body = text;
    } else {
      body = response.body;
    }

    return new Response(body, {
      status: response.status,
      headers: resHeaders,
    });
  },
};
