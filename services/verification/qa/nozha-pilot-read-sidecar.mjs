import { createServer } from "node:http";
import { open } from "node:fs/promises";
createServer(async (request, response) => {
  const send = (status, body) => {
    response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    response.end(JSON.stringify(body));
  };
  if (request.method !== "GET" || request.url !== "/api/public/projects/nozha") return send(404, {ok:false});
  try {
    const fd = await open("/feed/nozha.json");
    let data;
    try {
      const bytes = Buffer.alloc(128 * 1024 + 1);
      const { bytesRead } = await fd.read(bytes);
      if (bytesRead >= bytes.length) return send(503, {ok:false});
      data = JSON.parse(bytes.subarray(0, bytesRead));
    } finally { await fd.close(); }
    const now = Date.now();
    const expires = Date.parse(data.approvedUntil);
    if (!Number.isFinite(expires) || expires <= now) return send(404, {ok:false});
    const card = data.card;
    const age = now - Date.parse(card.refreshedAt);
    if (!(age >= -5000 && age <= 20000) || card.inventoryCurrent !== true ||
        card.schemaVersion !== 1 || card.source !== "CASAIMED_CRM" ||
        card.project?.id !== "3" || card.project?.code !== "561663" || card.project?.title !== "Nozha") return send(503, {ok:false});
    return send(200, data.publicDerivedPlan === true ? {ok:true,card,pilotPlan:data.pilotPlan ?? []} : {ok:true,card});
  } catch (error) { return send(error.code === "ENOENT" ? 404 : 503,{ok:false}); }
}).listen(8080, "0.0.0.0");
