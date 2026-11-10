import { hexHash } from "./identity";
import { type Env, HttpError } from "./platform";

// Trust only the edge-supplied address, never X-Forwarded-For or a body field.
export async function clientIpKey(request: Request, env: Env): Promise<string> {
  const value = request.headers.get("CF-Connecting-IP");
  if (!value) {
    if (env.ENVIRONMENT === "development") return hexHash("development-loopback");
    throw new HttpError(503, "client_ip_metadata_required");
  }
  let normalized: string;
  if (/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/.test(value) && value.split(".").every((part) => Number(part) <= 255)) {
    normalized = value.split(".").map(Number).join(".");
  } else {
    try {
      if (!/^[0-9a-fA-F:.]+$/.test(value) || !value.includes(":")) throw new Error();
      normalized = new URL(`http://[${value}]/`).hostname;
    } catch { throw new HttpError(400, "invalid_client_ip_metadata"); }
  }
  return hexHash(normalized);
}
