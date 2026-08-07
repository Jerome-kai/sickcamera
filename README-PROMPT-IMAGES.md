# Prompt reference images

Branch: `prompt-images`. On `main`, a prompt is text only — the camera sends
your photo plus the prompt body. This branch lets each prompt carry an image
too, so the model sees an example of what you are asking for.

Useful when words are the wrong tool: a specific character, a friend's face, a
particular art style, your own earlier output you want to stay consistent with.

## Using it

1. Open the camera's web UI and go to the **Prompts** tab.
2. Under any prompt, press **Add image** and pick a photo.
3. Take a shot with that prompt selected. The captured frame and the reference
   image are sent together.

Prompts holding an image show a small photo marker in the camera's own prompt
picker, so you can tell them apart without a phone.

Press **Replace image** to swap it, **Remove image** to go back to text only.
Editing a prompt's title or body never disturbs its image.

## How the model is told to use it

The camera already had this wiring for magic mode; this branch reuses it. The
captured photo stays the subject and the attachment is instruction, not content
to copy wholesale — `openai_client.py` appends:

> Use any additional attached images only as reference images for inspiration
> [...] using the reference image details where helpful.

So the reference steers style and detail rather than replacing your photo.

## Differences from `main`

| | `main` | `prompt-images` |
|---|---|---|
| Prompt fields | title, body | title, body, **reference image** |
| Images sent per shot | 1 (the photo) | 1 or 2 (photo + reference) |
| `data/prompts.json` | `{title, body}` | adds `reference_image` |
| Image storage | — | `data/prompt-references/<prompt-id>.jpg` |
| Endpoints | — | `POST /api/prompts/reference`, `POST /api/prompts/reference/delete`, `GET /prompt-references/<file>` |

Uploads are re-encoded to JPEG and downscaled to `IMAGE_GEN_INPUT_WIDTH` ×
`IMAGE_GEN_INPUT_HEIGHT` on arrival, so a phone photo does not bloat the SD card
or the request. Uploads over 12 MB are refused.

`data/prompts.json` stays compatible in both directions: `main` ignores the
extra field, and this branch treats a missing one as "no image".

## Cost

A second image means more input tokens per generation, and image-editing models
charge per input image. Text-only prompts on this branch cost exactly what they
do on `main` — the reference is only sent by prompts that have one.

## API support

Needs an endpoint that accepts multiple input images.

- `IMAGE_GEN_API=edits` (direct OpenAI) — supported, images go as an array.
- `IMAGE_GEN_API=chat` (Vercel AI Gateway and most gateways) — supported, each
  image becomes an `image_url` content part.
- `IMAGE_GEN_API=generations` (SiliconFlow and other China endpoints) — **not
  supported**; that API takes a single image, so references are skipped and a
  warning is logged. Text prompts are unaffected.

## Switching back

```
git checkout main
```

Attached images stay in `data/prompt-references/` and reappear if you switch
back. To drop them entirely, remove that folder and the `reference_image` keys
from `data/prompts.json`.
