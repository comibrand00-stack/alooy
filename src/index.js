const UPSTREAM = "https://n.alooytv14.xyz";
const REPO_JSON = {
  name: "AlooTV repository",
  description: "AlooTV & JoooTV - أفلام ومسلسلات",
  manifestVersion: 1,
  pluginLists: [
    "https://raw.githubusercontent.com/USERNAME/REPO_NAME/builds/plugins.json"
  ]
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === "/repo.json") {
      return new Response(JSON.stringify(REPO_JSON, null, 2), {
        headers: {
          "Content-Type": "application/json",
          "Access-Control-Allow-Origin": "*",
          "Cache-Control": "no-cache"
        }
      });
    }

    const upstreamUrl = new URL(UPSTREAM);
    upstreamUrl.pathname = url.pathname;
    upstreamUrl.search = url.search;

    const headers = new Headers(request.headers);
    headers.set("Host", upstreamUrl.host);
    headers.set("Referer", UPSTREAM + "/");
    headers.set("Origin", UPSTREAM);

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
      text = text.replace(/https:\/\/n\.alooytv14\.xyz/g, url.origin);
      text = text.replace(/href="\//g, `href="${url.origin}/`);
      text = text.replace(/src="\//g, `src="${url.origin}/`);
      text = text.replace(/action="\//g, `action="${url.origin}/`);
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