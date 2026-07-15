# Hardware — Orange Pi Zero 2 build

This fork of [openai/imagegencam](https://github.com/openai/imagegencam) targets an
**Orange Pi Zero 2** (Allwinner H616, Ubuntu server) instead of the Raspberry Pi Zero 2 W.

Parts used:

| Part | Replaces |
|---|---|
| Orange Pi Zero 2 | Raspberry Pi Zero 2 W |
| 3.5" SPI TFT, **ST7796U**, 320×480 IPS, capacitive touch (lcdwiki MSP3526; touch unused) | Pimoroni Display HAT Mini (ST7789, 320×240) |
| 1× mechanical keyboard switch (shutter) + 4× 6×6 mm tactile switches (UI) | HAT's 4 buttons + PiSugar shutter button |
| 3.6mm USB UVC board camera | CSI Spy Camera for Pi Zero |
| 5V 18650 battery pack, 2800mAh (integrated charge/protect/boost; USB-C in, JST 2-pin 5V/3A out) | PiSugar 3 |

## 26-pin header pinout (Orange Pi Zero 2)

```
                3.3V  1 ●● 2   5V
     PH5 (I2C3_SDA)   3 ●● 4   5V
     PH4 (I2C3_SCK)   5 ●● 6   GND
                 PC9  7 ●● 8   PH2 (UART5_TX)
                 GND  9 ●● 10  PH3 (UART5_RX)
                 PC6 11 ●● 12  PC11
                 PC5 13 ●● 14  GND
                 PC8 15 ●● 16  PC15
                3.3V 17 ●● 18  PC14
    PH7 (SPI1_MOSI) 19 ●● 20  GND
    PH8 (SPI1_MISO) 21 ●● 22  PC7
     PH6 (SPI1_CLK) 23 ●● 24  PH9 (SPI1_CS0)
                 GND 25 ●● 26  PC10
```

libgpiod line numbers on `/dev/gpiochip0` are `port_index × 32 + pin`:
**PCn = 64+n, PHn = 224+n** (e.g. PC9 → 73). Verify with `gpioinfo`.

## Display wiring (SPI1)

The build uses a **3.5" ST7796U 320×480 IPS module with capacitive touch**
(lcdwiki **MSP3526**, "3.5 TFT SPI 480X320 V1.0") on its 14-pin 2.54 mm header.
The 0.5 mm FPC connector duplicates the same signals — use the pin header, leave
the FPC empty. `DISPLAY_CONTROLLER=st7796` (the default) drives it in 480×320
landscape; `ili9341` is also available for 2.8" 240×320 modules (same wiring).

| Module pin (14P) | Header pin | Signal | gpiod line |
|---|---|---|---|
| VCC | 2 | **5V** (module spec is 5.0 V, 0.5 W; onboard regulator) | — |
| GND | 6 | GND | — |
| LCD_CS | 24 | PH9 / SPI1_CS0 (hardware CS) | — |
| LCD_RST | 22 | PC7 | 71 |
| LCD_RS | 26 | PC10 — data/command | 74 |
| SDI (MOSI) | 19 | PH7 / SPI1_MOSI | — |
| SCK | 23 | PH6 / SPI1_CLK | — |
| LED | 16 | PC15 — backlight control | 79 |
| SDO (MISO) | — | not connected | — |
| CTP_SCL / CTP_SDA / CTP_RST / CTP_INT | — | not connected (FT6336U touch unused) | — |
| SD_CS | — | not connected (if present) | — |

The SPI/control lines are 3.3 V — that's what the Orange Pi drives and the module
accepts it fine even on 5 V supply. Backlight is rated 95 mA total; the LED pin is
a control input on this module, so PC15 can drive it. If the backlight misbehaves
(dim, or the board browns out), tie LED to 3.3 V (pin 1) for always-on instead —
the software copes, `set_backlight` just stops having a visible effect.

## Button wiring (1 MX shutter + 4× 6×6 mm tactile switches)

The shutter is a full mechanical keyboard switch (unmistakable by feel); the four UI
buttons are 6×6 mm through-hole tactile switches, soldered to a small perfboard strip
mounted behind the front panel on standoffs, stems poking through Ø4 mm holes (pick
the stem height — 4.3 to 10 mm variants exist — to clear wall + standoff + board).

**4-leg tact switches:** the 4 pins are two internally-joined pairs — wire two
**diagonal** pins (diagonal is always a valid pair) or beep-test with a multimeter;
using two joined pins reads as "always pressed".

One contact of each switch to the listed header pin, the other to any GND pin
(6, 9, 14, 20, 25) — daisy-chain one GND wire across all five. Internal pull-ups are
enabled in software; pressed = line to ground. No resistors needed.

| Function | Header pin | Line | Notes |
|---|---|---|---|
| **Shutter** | 7 | PC9 (73) | short press = photo, hold >0.6s = Magic mode seed |
| ui_down (A) | 11 | PC6 (70) | menu down |
| ui_up (B) | 13 | PC5 (69) | menu up |
| ui_album (X) | 15 | PC8 (72) | album / gallery |
| ui_prompt (Y) | 18 | PC14 (78) | prompt picker |

All pin assignments are overridable in `.env` (`BUTTON_*_PIN`, `DISPLAY_*_PIN`) if your
chassis routing prefers different pins.

## Hot shoe flash trigger (optional)

A standard hot-shoe socket fired through a **MOC3021** opto-triac on **PC11**
(header pin 12, gpiod line 75). The triac isolates the board from the flash's
sync voltage (old flashes can put 100 V+ on the shoe) and conducts either
polarity, so center/frame orientation doesn't matter.

```
PC11 (pin 12) ──330Ω── 1 ┌─────────┐ 6 ── shoe center contact
                         │ MOC3021 │
GND  (pin 14) ──────── 2 └─────────┘ 4 ── shoe metal frame
```

| MOC3021 pin | Connects to | Header pin |
|---|---|---|
| 1 (LED anode) | 330Ω resistor → PC11 | 12 |
| 2 (LED cathode) | GND | 14 |
| 6 (main terminal) | hot shoe center contact | — |
| 4 (main terminal) | hot shoe metal frame | — |
| 3, 5 | not connected | — |

Enable in `.env`: `HOTSHOE_ENABLED=1` (pin and pulse length via `HOTSHOE_PIN`,
`HOTSHOE_PULSE_MS`). The app fires the trigger on every shutter capture and
waits for the next camera frame so the flash lights the photo that gets sent
for generation.

**If the flash doesn't fire reliably:** 3.3 V through 330Ω puts ≈6 mA through
the LED, below the MOC3021's guaranteed 15 mA trigger current (most units fire
anyway). Fixes, in order of preference: use a **MOC3023** (5 mA guaranteed
trigger, same pinout), or drop the resistor to 150Ω.

## Camera

USB UVC board camera into the USB-A port → `/dev/video0`. Check with:

```bash
ls /dev/video*          # should list video0
```

If it enumerates at a different index, set `CAMERA_DEVICE` in `.env`.

### Camera on the 13-pin header (no USB-A plug needed)

The Zero 2's second header exposes **two full USB 2.0 host ports** (user manual
§2.9 / §3.17 — the same ports the official expansion board uses). Cutting the
camera's USB cable and soldering it here frees the chassis from any USB-A cutout.
**Pin 1 is the end of the 13-pin row nearest the Ethernet jack.**

| 13-pin header | Signal | USB cable wire |
|---|---|---|
| 1 | 5V | red |
| 2 | GND | black (+ shield braid, Pi side only) |
| 3 | USB2-DM (D−) | white |
| 4 | USB2-DP (D+) | green |
| 5 / 6 | USB3-DM / USB3-DP | spare second USB port |

Keep D+/D− twisted together and short (<10 cm if possible) — it's a 480 Mbit/s
differential pair. Verify with `lsusb` then `ls /dev/video*` after soldering.
(Pins 7–13 stay free: audio line-out, TV-out, IR and three GPIOs.)

## Power

The board needs regulated **5V/2A**. Instead of the USB-C port, power it through the
header 5V pins — the officially documented method (user manual §2.8: red wire to a
5V pin, black to GND; "remember not to connect the wrong pin").

Battery: a **5V 18650 battery pack** with integrated charging, protection and boost
(USB-C charge input, JST 2-pin 5V/3A output; e.g. NICJOY LI-1S1P-5V2800, 22×67 mm).
~2 Ah usable at 5V ≈ 2–3h runtime. Wire its JST output to a small perfboard
"distribution board" that carries the master power switch and fans out:

```
JST 2-pin ─► [switch] ─► 5V rail ─┬─ 26P pins 2 + 4  (Pi — two wires share the current)
                                  ├─ display VCC (5V)
                                  └─ camera red (or 13P pin 1)
                        GND rail ─┬─ 26P pins 6 + 9
                                  ├─ display GND
                                  └─ camera black
```

Cautions: regulated 5V only; there is **no fuse or reverse-polarity protection** on
this path — double-check +/− before first power-up; use two 5V + two GND header
pins and ≥0.5 mm² (20–22 AWG) wire. The pack's USB-C charge pigtail mounts through
the case wall as the charge port. Test whether the pack does charge-while-output
(pass-through); cheap packs often power off the output while charging.

## Legacy 4.9 kernel image (official "Orange Pi Focal" Ubuntu 20.04)

The stock orangepi.org Ubuntu 20.04 image runs **Linux 4.9.170-sun50iw9** and Python 3.8.
The app supports it via automatic fallbacks, with three extra requirements:

1. **Python 3.10+ from deadsnakes** (the setup scripts detect and use it):

   ```bash
   sudo add-apt-repository ppa:deadsnakes/ppa
   sudo apt update
   sudo apt install python3.10 python3.10-venv python3.10-dev
   ```

2. **GPIO runs over sysfs** (`GPIO_BACKEND=auto` handles this — the modern gpiod
   interface needs kernel 5.10+). Nothing to configure.

3. **Run as root.** sysfs GPIO and the /dev/mem pull-up configuration need root on 4.9:
   - one-off runs: `sudo ./scripts/run.sh`, `sudo .venv/bin/python3 scripts/display_test.py`
   - service install: `SERVICE_USER=root ./scripts/install_service.sh`

   Internal pull-ups for the buttons are written directly to the H616 pin-controller
   registers at startup. If you'd rather not run as root, fit external **10kΩ resistors
   from each button pin to 3.3V** and set `SUNXI_SET_PULLUPS=0`.

SPI enablement on this image: edit `/boot/orangepiEnv.txt` as described below (older
orangepi-config builds have no Hardware/overlay menu). Confirm with `ls /dev/spidev*`
after reboot. **On this legacy image the SPI1 device appears as `/dev/spidev1.1`**
(chip-select 1 — a quirk of the 4.9 device tree; wiring is unchanged, CS stays on
header pin 24). Set `DISPLAY_SPI_DEV=1` in `.env` (the default). Modern images expose
`/dev/spidev1.0` instead → set `DISPLAY_SPI_DEV=0`.

If sysfs GPIO numbering doesn't match (button test shows nothing), check
`cat /sys/class/gpio/gpiochip*/base` — if the base isn't 0, set `GPIO_SYSFS_BASE`
in `.env` to that base value.

A reflash to the current Orange Pi Zero2 Ubuntu 22.04 image (kernel 5.16+) or Armbian
removes all three caveats, if you ever feel like it.

## One-time OS setup

1. **Enable SPI1** — edit `/boot/orangepiEnv.txt` (Armbian: `/boot/armbianEnv.txt`) and add:

   ```
   overlays=spi-spidev
   param_spidev_spi_bus=1
   ```

   Reboot, then confirm a bus-1 device exists: `ls /dev/spidev*` should show
   `/dev/spidev1.1` (legacy 4.9 image → `DISPLAY_SPI_DEV=1`, the default) or
   `/dev/spidev1.0` (modern images → `DISPLAY_SPI_DEV=0`).

2. **NetworkManager** (for the on-camera Wi-Fi menu) — Ubuntu server ships with netplan +
   systemd-networkd:

   ```bash
   sudo apt install network-manager
   ```

   and set the netplan renderer to NetworkManager (`renderer: NetworkManager` in
   `/etc/netplan/*.yaml`, then `sudo netplan apply`). If your Wi-Fi interface is not
   `wlan0` (`ip link` to check), set `WIFI_INTERFACE` in `.env`.

3. **App setup**:

   ```bash
   cd software
   ./scripts/setup.sh          # venv + deps + .env (paste your gateway/OpenAI key)
   # then edit software/.env: set OPENAI_BASE_URL if using a gateway
   ```

   Never paste your API key into chats or commit it — it lives only in `software/.env`.

## Bring-up sequence (recommended)

1. **Display first**: `.venv/bin/python3 scripts/display_test.py` — color bars + corner
   labels. Fix orientation/colors via `.env` before anything else.
2. **Buttons**: `sudo .venv/bin/python3 scripts/button_test.py` — prints DOWN/up per
   named button as you press each switch. (`gpiomon -c gpiochip0 73 70 69 72 78` works
   too, but only shows raw line numbers.)
3. **Camera**: `ls /dev/video*`; the app's preview is the real test.
3b. **Hot shoe** (if wired): `sudo .venv/bin/python3 scripts/hotshoe_test.py` — pulses
   the trigger 5×; a mounted flash should pop each time.
4. **Full app from SSH**: `./scripts/run.sh` — live preview on the panel, buttons, shutter.
5. **Generation**: with `OPENAI_API_KEY` (+ `OPENAI_BASE_URL` for a gateway) set, take a
   photo and watch the queue. Verify the API path before the hardware arrives with
   `.venv/bin/python3 scripts/gateway_test.py`. Gateway note: most gateways (including
   the Vercel AI Gateway) do **not** expose `/v1/images/edits`; use the chat-completions
   editing path instead — in `.env` set `IMAGE_GEN_API=chat` and an image-capable model
   with provider prefix, e.g. `IMAGE_GEN_MODEL=google/gemini-2.5-flash-image`. Magic
   mode uses `/v1/responses` (`MAGIC_MODE_MODEL` also needs a provider prefix).
6. **Services**: `./scripts/install_service.sh`, then
   `sudo systemctl enable --now imagegencam.service`, reboot test.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Panel colors inverted / washed out | toggle `DISPLAY_INVERT` in `.env` |
| Image mirrored or upside down | toggle `DISPLAY_ROTATION_180` |
| Red and blue swapped in the camera preview | toggle `CAMERA_SWAP_RED_BLUE` |
| no `/dev/spidev1.*` device | overlay not applied — recheck `orangepiEnv.txt`, reboot |
| display driver can't open SPI | match `DISPLAY_SPI_DEV` to `ls /dev/spidev*`: `spidev1.1` → 1 (legacy image), `spidev1.0` → 0 |
| `Permission denied` on gpiochip/spidev | re-login after `install_service.sh` (group membership), or run once with `sudo` to confirm wiring |
| Buttons never register | pull-up bias needs a modern kernel (5.15+ image); check `gpioinfo` shows the lines as unused; worst case add external 10kΩ pull-ups to 3.3V |
| UI has black side bars | expected (4:3 UI on a 3:2 panel); `DISPLAY_FIT=stretch` to fill |
| Wi-Fi menu empty | NetworkManager not installed/managing the interface; see OS setup step 2 |
