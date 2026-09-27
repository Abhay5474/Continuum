import { Micro, Note, Switch } from "../../system/primitives";
import { Code } from "../../system/hub";
import { Pipeline } from "./types";

/* -------------------------------------------------------------------------- *
 * Endpoint
 * -------------------------------------------------------------------------- */

export function Endpoint({
  p,
  busy,
  onEnable,
}: {
  p: Pipeline;
  busy: boolean;
  onEnable: (next: boolean) => void;
}) {
  return (
    <div className="space-y-5">
      <Switch
        checked={p.enabled}
        busy={busy}
        onChange={onEnable}
        label="Accept requests from your application"
        hint="Off by default. While it is off the endpoint answers 400 and nothing runs — so a half-configured pipeline cannot be reached by mistake."
      />
      <div>
        <Micro>The call your application makes</Micro>
        <div className="mt-1.5">
          <Code>
{`curl -X POST https://your-continuum/api/gateway/pipeline/${p.name} \\
  -H "Authorization: Bearer cnt_live_..." \\
  -H "content-type: application/json" \\
  -d '{"input":{"image":"<base64>"},"prompt":"What should I do?"}'`}
          </Code>
        </div>
        <Note className="mt-2">
          The response carries the answer and the chain behind it. Your application can show its user
          the working, or ignore the field entirely — but it never sees which provider ran or what
          credential was used.
        </Note>
      </div>
    </div>
  );
}
