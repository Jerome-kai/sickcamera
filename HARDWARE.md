# Hardware — Orange Pi Zero 2 build

This fork of [openai/imagegencam](https://github.com/openai/imagegencam) targets an
**Orange Pi Zero 2** (Allwinner H616, Ubuntu server) instead of the Raspberry Pi Zero 2 W.

Parts used:

| Part | Replaces |
|---|---|
| Orange Pi Zero 2 | Raspberry Pi Zero 2 W |
| 2.8" SPI TFT, **ILI9341**, 240×320, capacitive touch (touch unused) | Pimoroni Display HAT Mini (ST7789, 320×240) |
| 5× mechanical keyboard switches | HAT's 4 buttons + PiSugar shutter button |
| 3.6mm USB UVC board camera | CSI Spy Camera for Pi Zero |
| Dual-18650 power bank PCB (5V/2A boost out) | PiSugar 3 |

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

Two panel types are supported — set `DISPLAY_CONTROLLER` in `.env`:

- **`ili9341`** — 2.8" 240×320 modules ("2.8 TFT SPI 240X320 V1.0", including the
  capacitive-touch version with the 14-pin header). Runs 320×240 landscape, a
  pixel-perfect match for the app's UI (no scaling).
- **`st7796`** — 3.2" 480×320 ST7796S/U modules. The 320×240 UI is upscaled and
  pillarboxed.

Both wire to the **same header pins**; only the pin names printed on the module differ:

| 2.8" module (14P) | 3.2" ST7796 module | Header pin | Signal | gpiod line |
|---|---|---|---|---|
| VCC | VCC | 17 | 3.3V | — |
| GND | GND | 20 | GND | — |
| LCD_CS | CS | 24 | PH9 / SPI1_CS0 (hardware CS) | — |
| LCD_RST | RESET | 22 | PC7 | 71 |
| LCD_RS | DC / RS | 26 | PC10 | 74 |
| SDI (MOSI) | SDI / MOSI | 19 | PH7 / SPI1_MOSI | — |
| SCK | SCK | 23 | PH6 / SPI1_CLK | — |
| LED | LED (backlight) | 16 | PC15 | 79 |
| SDO (MISO) | SDO / MISO | — | not connected | — |
| CTP_SCL / CTP_SDA / CTP_RST / CTP_INT | T_* touch pins | — | not connected (touch unused) | — |
| SD_CS | — | — | not connected (SD slot unused) | — |

Backlight note: the LED pin is driven directly from PC15. If the backlight is dim
or the board browns out when it switches on (some modules draw 50 mA+ on LED),
wire LED to 3.3V (pin 1) instead for an always-on backlight — the software copes,
`set_backlight` just stops having a visible effect.

If the 2.8" panel shows garbage at 40 MHz SPI, drop `DISPLAY_SPI_HZ` to `24000000`
(ILI9341 is officially slower than ST7796; most modules still take 40 MHz fine).

## Button wiring (5 mechanical switches)

One leg of each switch to the listed header pin, the other leg to any GND pin
(6, 9, 14, 20, 25). Internal pull-ups are enabled in software; pressed = line to ground.
No resistors needed.

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

## Power

The board needs regulated **5V/2A** into its USB-C port. Li-Ion cells are 3.7V no matter
how many you parallel — parallel adds capacity, not voltage — so use a dual-18650 power
bank PCB module (charging + protection + 5V/2A boost in one board) and feed its 5V output
to the USB-C connector. One cell ≈ 2–3h runtime, two in parallel ≈ all-day.

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
2. **Buttons**: `gpiomon -c gpiochip0 73 70 69 72 78` (or just run the app) and press each
   switch — you should see edges.
3. **Camera**: `ls /dev/video*`; the app's preview is the real test.
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
