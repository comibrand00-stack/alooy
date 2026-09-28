const UPSTREAM = "https://a.sh4u.cam";

const DOMAINS = [
  "https://sh4u.cam",
  "https://a.sh4u.cam",
  "https://sshahed4u.net",
  "https://www.sshahed4u.net",
];

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    const upstreamUrl = new URL(UPSTREAM);
    upstreamUrl.pathname = url.pathname;
    upstreamUrl.search = url.search;

    const headers = new Headers(request.headers);
    headers.set("Host", upstreamUrl.host);
    headers.set("Referer", UPSTREAM + "/");
    headers.set("Origin", UPSTREAM);
    headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
    headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
    headers.set("Accept-Language", "ar,en-US;q=0.9,en;q=0.8");

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
        text = text.replace(new RegExp(domain.replace(/[.]/g, "\\."), "g"), url.origin);
      }
      text = text.replace(/https:\/\/a\.sh4u\.cam/g, url.origin);
      text = text.replace(/https:\/\/sh4u\.cam/g, url.origin);
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