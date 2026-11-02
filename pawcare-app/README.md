# PawCare

A pet first-aid assistant built on Continuum. Owners describe what happened or
send a photo of an injury; PawCare answers with the pet's history in mind and
shows how sure it is.

It is an example of an application a developer builds with a Continuum API
key: one page (`index.html`) and a small server (`server.py`, Python standard
library only) that holds the key so the browser never sees it.

## What it does, and which part of Continuum does it

| In PawCare | In Continuum | Where it shows in the console |
|---|---|---|
| **Photo check**: attach a photo, get findings with confidence bars, advice, and a note on whether the advice stays within what was found | Your pipeline (`/api/gateway/pipeline/{name}/upload`): your detection model (e.g. Roboflow) looks first, the findings become evidence, the confidence policy decides how firmly the model may speak, the model answers, the answer is checked against the evidence | Pipelines → the run's trace |
| **Chat** about symptoms, with the full conversation | The gateway (`/v1/chat/completions`): routing, failover, firewall, semantic cache, cascade, context optimizer, whatever the account has on | Gateway, Traffic |
| **Remembers each pet**: facts you save, and every photo check, come back into later answers | Memory (`/api/gateway/memory/...`), one scope per pet | Context → Memory |
| **Measure confidence**: shows how far the model agrees with itself | Semantic uncertainty, on request | Answer Assurance → Confidence |
| **Emergency**: the request jumps the queue with a 15 s deadline | Priority & deadlines | Traffic Control → Priority & deadlines |
| **👍 / 👎** on any answer | Feedback (`/api/gateway/feedback`), which Autopilot learns from when it tunes routing | Autopilot |
| **Vet summary**: a handover note from the chat and the pet's history, to copy or download | The gateway with quality-first routing | Gateway |
| **What Continuum did**: under each answer, one line per feature that acted on it | The `applied` list on every response | — |

## Set up (once)

1. **Continuum is running** (`http://localhost:8080` by default), with a model
   provider connected.
2. **Issue an API key** in the console: *API Keys & Providers → Apps & API
   keys*. Copy it; you will paste it into PawCare's own terminal window in step 4,
   never into a web page or a chat.
3. **For photo checks, create a pipeline.** A *specialist* (your Roboflow model,
   on the Specialists page) is not a pipeline: the pipeline is what runs it, hands
   its findings to the language model and returns the answer. In the console:
   *Pipelines → New pipeline*, name it with letters, digits, `-` and `_` only
   (e.g. `animal-injury`, no spaces), input **an image**, and add your injury
   specialist as its step. Then, in the pipeline's settings, turn on:
   - **Confidence policy**, so weak findings are hedged rather than asserted;
   - **Answer verification → Enforce**, so an answer that claims more than the
     photo showed is replaced.

   Optional, for the toggles in the composer:
   - *Answer Assurance → Confidence*: set the mode to **On demand**, so
     *Measure confidence* works.
   - *Traffic Control → Priority & deadlines*: switch it **on**, so *Emergency*
     changes the scheduling.
4. **Start PawCare.** On Windows, double-click `start.bat` (or run
   `.\start.ps1` in PowerShell). Elsewhere: `python3 server.py`.

   The first start asks for Continuum's API address (`http://localhost:8080`, the backend, not the console's `:5173`), the API key (typing is hidden)
   and the pipeline name, and saves them in `pawcare.local`, which git ignores.
   Your browser opens at **http://localhost:5070**.

To enter different details later, delete `pawcare.local` (or run
`.\start.ps1 -Reconfigure`). Environment variables override the file:
`CONTINUUM_URL`, `CONTINUUM_API_KEY`, `PAWCARE_PIPELINE`, `PAWCARE_PORT`, and
`PAWCARE_NO_BROWSER=1` to stop the browser opening.

## Using it

1. **Add your pet**: name, species, breed, age, weight, and notes (conditions,
   medicines) that should go into every answer.
2. **Ask** in the box at the bottom, or **attach a photo** (the picture button,
   drag and drop, or paste). With a photo, the text is your question about it.
3. Save standing facts under **Remembered** in the sidebar, e.g. "Allergic to
   chicken". Photo checks are saved there automatically.
4. **Vet summary** (top right) writes a handover note for your vet.

Chats and pet profiles stay in this browser. What PawCare remembers is in your
Continuum account, under the scope `pawcare/pet-<id>`.

## Keep the key on the server

The key is read by `server.py` and sent only from there to Continuum. The page
calls `server.py`'s own `/api/...` routes and never sees the key. A key written
into a web page's JavaScript can be read by anyone who opens the page, and it
gives full use of your account: keys belong on a server, never in front-end
code. If a key has ever been in a page, revoke it in *API Keys & Providers* and
issue a new one.

## If something goes wrong

| What you see | What to do |
|---|---|
| "Continuum is not reachable" in the sidebar | Start the backend; check the address in `pawcare.local`. |
| "Photo checks off" | No pipeline name was entered. Delete `pawcare.local` and start again. |
| "Continuum has no pipeline called …" | Create the pipeline (step 3), or delete `pawcare.local` and enter the name exactly as the Pipelines page shows it. The specialist's name will not work. |
| A photo check says *The detection model did not answer* | Open the trace in the console (Pipelines → the run): the specialist step says why (wrong model path, Roboflow key, quota). |
| `Port 5070 is in use` | PawCare is already running, or set `PAWCARE_PORT` / `-Port` to another port. |
| *Measure confidence* shows nothing | Set *Answer Assurance → Confidence* to **On demand**. |

PawCare gives first-aid guidance, not a diagnosis.
