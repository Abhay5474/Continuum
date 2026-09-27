import { useState } from "react";
import { Note } from "../../system/primitives";
import { Field, Primary, Rail, Row, SidePanel } from "../../system/hub";
import { Select } from "../../system/controls";
import { INPUT_LABEL, Specialist } from "./types";

/* -------------------------------------------------------------------------- *
 * Creation
 * -------------------------------------------------------------------------- */

export function NewPipeline({
  open,
  specialists,
  busy,
  onClose,
  onAdd,
}: {
  open: boolean;
  specialists: Specialist[];
  busy: boolean;
  onClose: () => void;
  onAdd: (b: { name: string; description: string; inputKind: string; systemPrompt: string; steps: number[] }) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [inputKind, setInputKind] = useState("image");
  const [systemPrompt, setSystemPrompt] = useState("");
  const [steps, setSteps] = useState<number[]>([]);

  const toggleStep = (id: number) =>
    setSteps((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  const submit = () => {
    onAdd({ name: name.trim(), description, inputKind, systemPrompt, steps });
    setName("");
    setDescription("");
    setSystemPrompt("");
    setSteps([]);
    onClose();
  };

  return (
    <SidePanel
      open={open}
      title="New pipeline"
      subtitle="Off until you enable it"
      onClose={onClose}
      footer={
        <div className="flex items-center justify-between gap-3">
          <span className="text-[11.5px] text-slate-600">
            {steps.length === 0 ? "Pick at least one specialist" : `${steps.length} in the chain`}
          </span>
          <Primary disabled={busy || !name.trim() || steps.length === 0} onClick={submit}>
            Create pipeline
          </Primary>
        </div>
      }
    >
      <Field label="Name">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="animal-triage"
          className="w-full focus:border-[color:var(--accent-edge)] field"
        />
        <p className="mt-1 text-[11.5px] text-slate-600">
          This goes in the URL your application calls, so letters, digits, hyphen and underscore only.
        </p>
      </Field>

      <Field label="Input your app sends">
        <Select
          value={inputKind}
          onChange={(e) => setInputKind(e.target.value)}
        >
          <option value="image">Image</option>
          <option value="text">Text</option>
          <option value="json">Data</option>
          <option value="audio">Audio</option>
        </Select>
      </Field>

      <Field label="What it does">
        <input
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Photo of an injured animal in, first-aid advice out"
          className="w-full focus:border-[color:var(--accent-edge)] field"
        />
      </Field>

      <Field label="Standing instructions for the model">
        <textarea
          value={systemPrompt}
          onChange={(e) => setSystemPrompt(e.target.value)}
          rows={4}
          placeholder="You are advising a member of the public on immediate first aid for an injured animal. Be brief and practical. Always say when a vet is needed."
          className="w-full focus:border-[color:var(--accent-edge)] field"
        />
        <Note className="mt-1">
          Sent with every call, so your application does not have to repeat it. The findings and the
          user's own question are added underneath.
        </Note>
      </Field>

      <Field label="Specialists, in the order they run">
        <div className="-mx-1">
          <Rail>
            {specialists.map((s) => {
              const idx = steps.indexOf(s.id);
              return (
                <Row
                  key={s.id}
                  mark={
                    <span
                      className="readout grid h-6 w-6 shrink-0 place-items-center rounded-full text-[10px]"
                      style={
                        idx >= 0
                          ? { background: "var(--accent-strong)", color: "#fff" }
                          : { background: "rgb(var(--edge))", color: "var(--text-3)" }
                      }
                    >
                      {idx >= 0 ? idx + 1 : "·"}
                    </span>
                  }
                  title={s.name}
                  subtitle={`takes ${INPUT_LABEL[s.inputKind] ?? s.inputKind}`}
                  selected={idx >= 0}
                  onClick={() => toggleStep(s.id)}
                />
              );
            })}
          </Rail>
        </div>
      </Field>
    </SidePanel>
  );
}
