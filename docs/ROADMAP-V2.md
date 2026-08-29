# ImageGenCam V2 — Product Roadmap

V1 works: it takes a photo, an AI redraws it, the result lands on the screen and
in the web album. It also boots for five minutes, hangs together on dupont
wires, and greets a new owner with a published hotspot password. V2 treats
those as product defects, not quirks — the goal of this round is to practice
real engineering on a real (if unsellable) product.

The rule for everything below: **measure before rewriting, and fix causes, not
symptoms.**

---

## 1. Power & boot — the switch is the bug

### Diagnosis

The five-minute "boot" is not slow Python. The on/off switch is soldered
directly into the power line, so:

- every **off** is a hard power cut (which is also why store files could get
  corrupted — the software now survives that, but surviving is not the same as
  fixing);
- every **on** is a full cold boot of Ubuntu 20.04 plus service startup plus
  Wi-Fi association plus the camera-enumeration wait.

A Rust/C rewrite or a custom OS would attack the smallest slice of that time.
Both are **explicitly rejected** for v2: the app itself starts in seconds. The
OS boot and the power-cut-by-design are the cost, so power management is the
fix.

### Plan

1. **Measure first.** On the device:

   ```
   systemd-analyze
   systemd-analyze blame | head -20
   systemd-analyze critical-chain
   ```

   Everything in this section after this point gets justified (or deleted) by
   those numbers.

2. **Soft power on the carrier PCB** (see §2). A momentary button plus a
   P-MOSFET latch circuit:

   - press → the latch powers the board;
   - a GPIO **sense** line tells the app the button was pressed again → the app
     shows "shutting down", saves state, runs `systemctl poweroff`;
   - a GPIO **hold** line lets the OS cut its own power once shutdown
     completes.

   Parts cost about a dollar. Search terms to study: "soft latching power
   switch MOSFET", and the Raspberry Pi "OnOff SHIM" schematic, which is
   exactly this circuit.

3. **Sleep instead of off** for short pauses: backlight off
   (`DISPLAY_BACKLIGHT_PIN=79` is already software-controlled), camera
   released, CPU governor down, wake on the shutter button. Turns "pull it out
   of the bag" from minutes into instant.

4. **Trim the cold boot that remains**, guided by step 1: disable distro
   services the camera never uses, keep the existing
   `CAMERA_OPEN_TIMEOUT_SECONDS` retry loop (it exists because the service can
   legitimately start before the camera enumerates). Estimated landing zone:
   30–45 s cold boot — but the number that goes in this doc's next revision is
   the measured one.

**Done when:** cold boot is measured and under a minute; pressing the power
button performs a clean OS shutdown; sleep/wake works in under two seconds.

---

## 2. Carrier PCB — first board, scoped to succeed

### Scope

One 2-layer board that the Orange Pi Zero 2's 26-pin header and the ST7796
display header plug into. It replaces every dupont wire with a trace:

| Subsystem | Today (dupont) | On the board |
|---|---|---|
| Display | SPI1 + DC 74 / RST 71 / backlight 79 | routed traces |
| Buttons | 5 switches on PC lines (73, 70, 69, 72, 78) | board-mounted tactile switches |
| Hot shoe | MOC3021 on PC11 (line 75) + 330R | footprints on board |
| Camera | USB pigtail with a known-flaky solder joint | strain-relieved connector |
| Power | switch in the supply line | soft-power latch (§1) |

The pin map is already documented in `HARDWARE.md` and `software/.env.example`
— the schematic is a transcription exercise, which is the right difficulty for
a first board.

### Learning path

KiCad (free, the standard for this): schematic → assign real footprints and
check them against the physical parts with calipers → layout → DRC clean →
order at JLCPCB (~$10 for 5 boards, ~1 week). Then bring-up one subsystem at a
time with a multimeter before plugging in the Orange Pi.

**Budget for a respin.** The first board will have a mistake somewhere. That
is normal and is why fab is cheap; finding the mistake is the actual lesson.

### Non-goals

No custom SoC board (that's BGA soldering and 6+ layers — a different sport),
no fine-pitch parts, nothing under 0805 passives. The Orange Pi stays the
compute module.

**Done when:** the camera runs with zero dupont wires, and the case closes on
a thinner stack.

---

## 3. UX — usable by someone who isn't the builder

### Prompt-with-image (already built — ship it)

The `prompt-images` branch already lets a prompt carry a reference image:
uploaded from the web UI, stored in `data/prompt-references/`, persisted
across reboots, and sent alongside the photo in `chat` API mode. It has never
been tested on the device, which is the only reason it isn't on `main`.

**Done when:** on-device test passes (attach an image, shoot, journal shows
"2 reference image(s)"), branch merged to `main`.

### Wi-Fi manager in the button UI

The prompt button becomes a menu tab. From the device, without a phone:

- see saved networks and which one is active;
- add a network (the on-screen keyboard already exists for this);
- remove a saved network;
- the "multiple Wi-Fi" wish — phone hotspot at school, home Wi-Fi at home —
  needs **no new connection logic**: NetworkManager already auto-joins the
  best available saved profile. The camera just needs UI to manage the list.
  `wifi_manager.py` already never deletes profiles behind your back; the
  `wifi_menu` / `wifi_detail` screens in `controller.py` are the place to
  extend.

**Done when:** a camera with home Wi-Fi + a phone hotspot saved switches
between them with zero interaction.

### First-run tutorial

A guided overlay on first boot (one flag in the settings store): shutter →
prompt picker → album → "scan this QR for the web app". Skippable, re-runnable
from the diagnostics menu.

**Done when:** someone who has never seen the camera takes an AI photo and
finds it on their phone without being told anything.

---

## 4. Security — stop publishing the keys

- **Per-device random hotspot password**: generated on first boot, stored in
  `data/`, shown on the display's Wi-Fi setup screen next to the SSID. The
  `takeaphoto` default in `wifi_setup.py` (and in the repo, and in the docs)
  goes away.
- Noted for later, deliberately deferred: removing `portal_password` from the
  `/api/wifi/status` response, PIN-on-first-visit auth for the web UI. A phone
  app with pairing is rejected — a second codebase to maintain is
  overcomplication for this product, as suspected.

**Done when:** nothing printed in this repository can get a stranger onto the
camera's hotspot.

---

## 5. Sequence

Software leads (free, reversible); hardware follows what software learns.

1. **Measure boot** on-device — one evening; produces the numbers §1 acts on.
2. **Device-test `prompt-images`, merge to `main`** — done, only verification
   stands between it and shipping.
3. **Wi-Fi manager UI + tutorial + random AP password** — the software
   milestone.
4. **Sleep mode** — makes the camera feel fast before any hardware changes.
5. **Carrier-board schematic in KiCad**, including soft power — reviewed
   before any money is spent.
6. **Fab, assemble, bring-up**; add soft-power GPIO support to the app.
7. **Boot trim** per the measurements; **case redesign** around the thinner
   stack.

Milestones 1–4 need nothing but the camera and an evening each. Milestone 5 is
where the new skill gets learned; 6 is where it gets tested; 7 is polish.
