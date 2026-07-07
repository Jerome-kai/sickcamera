from __future__ import annotations

import html
import io
import json
import logging
import mimetypes
import zipfile
from email.utils import formatdate
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.parse import parse_qs, quote, unquote

from PIL import Image

from .config import CAMERA_USERNAME_MAX_LENGTH, PROMPT_TITLE_MAX_LENGTH


logger = logging.getLogger(__name__)

MAX_POST_BODY_BYTES = 128 * 1024


def json_for_inline_script(value: object) -> str:
    """Serialize JSON so user strings cannot break out of a script tag."""
    return (
        json.dumps(value)
        .replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


PAGE_STYLE = """
@font-face {
  font-family: "OrbitronUI";
  src: url("/assets/fonts/Orbitron-Regular.ttf") format("truetype");
  font-weight: 400 900;
  font-style: normal;
  font-display: swap;
}
* {
  box-sizing: border-box;
}
body {
  font-family: "OrbitronUI", "SFMono-Regular", "Menlo", "Consolas", monospace;
  margin: 0;
  color: #111;
  min-height: 100vh;
  background: linear-gradient(180deg, #d9d9d9 0%, #efefef 100%);
}
body[data-theme="aqua"] {
  --screen-bg: linear-gradient(180deg, #eef8ff 0%, #bfeef8 100%);
}
body[data-theme="silver"] {
  --screen-bg: linear-gradient(180deg, #f7f7f7 0%, #d7dde4 100%);
}
body[data-theme="lavender"] {
  --screen-bg: linear-gradient(180deg, #f6f1ff 0%, #d8d0ff 100%);
}
body[data-theme="mint"] {
  --screen-bg: linear-gradient(180deg, #f0fff8 0%, #c8f0df 100%);
}
body[data-theme="sunset"] {
  --screen-bg: linear-gradient(180deg, #fff5ef 0%, #ffd7bf 100%);
}
main {
  max-width: 1280px;
  margin: 0 auto;
  padding: 20px 20px 48px;
}
p {
  margin: 0;
}
.page-title {
  display: none;
}
.reference-layout {
  display: grid;
  grid-template-columns: 1fr;
  gap: 26px;
  justify-items: center;
}
.device-wrap {
  display: flex;
  justify-content: center;
}
.device {
  width: 430px;
  min-height: 840px;
  border-radius: 48px;
  padding: 26px 22px 28px;
  background:
    radial-gradient(circle at 30% 20%, rgba(255,255,255,0.95), rgba(241,241,241,0.8) 30%, rgba(216,216,216,0.75) 68%, rgba(195,195,195,0.95)),
    linear-gradient(180deg, #f6f6f6 0%, #d9d9d9 60%, #c9c9c9 100%);
  border: 4px solid #1f1f1f;
  box-shadow:
    inset 0 2px 1px rgba(255,255,255,0.9),
    inset 0 -8px 20px rgba(0,0,0,0.1),
    0 28px 90px rgba(0,0,0,0.28);
  position: relative;
}
.device::before {
  content: "";
  position: absolute;
  top: 12px;
  left: 50%;
  width: 126px;
  height: 26px;
  transform: translateX(-50%);
  border-radius: 999px;
  background: #070707;
  box-shadow: inset 0 -2px 4px rgba(255,255,255,0.08);
}
.statusbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 12px 18px;
  font-size: 0.95rem;
  font-weight: 700;
}
.screen-shell {
  border-radius: 12px;
  padding: 14px;
  background: #e4e4e4;
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,0.85),
    0 0 0 1px rgba(0,0,0,0.06);
}
.screen {
  position: relative;
  min-height: 270px;
  border-radius: 4px;
  overflow: hidden;
  padding: 14px;
  background: var(--screen-bg, linear-gradient(180deg, #eef8ff 0%, #bfeef8 100%));
  box-shadow: inset 0 1px 2px rgba(255,255,255,0.9);
}
.screen-inner {
  height: 240px;
  border-radius: 3px;
  overflow: hidden;
  position: relative;
  background: rgba(255,255,255,0.35);
}
.slideshow-image,
.gallery-image {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
  background: rgba(255,255,255,0.4);
}
.screen-counter {
  position: absolute;
  top: 12px;
  left: 12px;
  font-size: 1.05rem;
  color: #6bff61;
  text-shadow:
    -1px -1px 0 #001500,
    1px -1px 0 #001500,
    -1px 1px 0 #001500,
    1px 1px 0 #001500;
  z-index: 2;
}
.screen-icon {
  position: absolute;
  right: 14px;
  bottom: 12px;
  width: 32px;
  height: 32px;
  border: 3px solid #4f5d67;
  border-top: 0;
  border-radius: 0 0 9px 9px;
  opacity: 0.8;
}
.screen-icon::before,
.screen-icon::after {
  content: "";
  position: absolute;
  background: #4f5d67;
}
.screen-icon::before {
  left: 6px;
  right: 6px;
  top: 14px;
  height: 3px;
}
.screen-icon::after {
  width: 12px;
  height: 12px;
  right: -2px;
  top: -7px;
  border-right: 3px solid #4f5d67;
  border-bottom: 3px solid #4f5d67;
  background: transparent;
  transform: rotate(45deg);
}
.screen-brand {
  text-align: center;
  color: rgba(0,0,0,0.22);
  font-size: 0.95rem;
  font-style: italic;
  margin-top: 10px;
}
.screen-brand small {
  font-size: 0.6em;
}
.top-actions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  align-items: end;
  gap: 14px;
  margin: 14px 0 18px;
}
.tiny-button {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.tiny-button button,
.tiny-button a {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  border: 1px solid rgba(0,0,0,0.35);
  background:
    radial-gradient(circle at 35% 35%, #ffffff 0%, #eeeeee 38%, #cecece 100%);
  box-shadow: inset 0 1px 3px rgba(255,255,255,0.95), 0 1px 2px rgba(0,0,0,0.15);
  display: grid;
  place-items: center;
  color: #778;
  text-decoration: none;
  font-size: 0.8rem;
}
.tiny-button span {
  font-size: 0.58rem;
  color: rgba(0,0,0,0.52);
  letter-spacing: 0.04em;
}
.play-button {
  justify-self: end;
}
.play-button button {
  width: 64px;
  height: 64px;
  border-radius: 999px;
  border: 2px solid rgba(0,0,0,0.45);
  background:
    radial-gradient(circle at 35% 35%, #ffffff 0%, #efefef 36%, #d5d5d5 100%);
  box-shadow: inset 0 1px 3px rgba(255,255,255,0.95), 0 2px 6px rgba(0,0,0,0.12);
}
.play-icon {
  width: 28px;
  height: 18px;
  border: 2px solid #6a8bb0;
  position: relative;
  display: inline-block;
}
.play-icon::after {
  content: "";
  position: absolute;
  left: 8px;
  top: 2px;
  border-left: 12px solid #6a8bb0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
}
.wheel {
  width: 270px;
  height: 270px;
  margin: 0 auto;
  border-radius: 999px;
  position: relative;
  background:
    radial-gradient(circle at 35% 30%, rgba(255,255,255,0.95), rgba(248,248,248,0.92) 36%, rgba(223,223,223,0.96) 100%);
  border: 3px solid rgba(0,0,0,0.5);
  box-shadow:
    inset 0 3px 10px rgba(255,255,255,0.95),
    inset 0 -8px 24px rgba(0,0,0,0.08),
    0 0 0 4px rgba(255,255,255,0.4);
}
.wheel-center {
  position: absolute;
  inset: 95px;
  border-radius: 999px;
  border: 2px solid rgba(0,0,0,0.32);
  background:
    radial-gradient(circle at 35% 35%, #ffffff 0%, #f0f0f0 40%, #d8d8d8 100%);
  display: grid;
  place-items: center;
  color: rgba(0,0,0,0.18);
  font-size: 1.5rem;
  font-weight: 700;
}
.wheel-arrow {
  position: absolute;
  width: 26px;
  height: 26px;
  border: 0;
  background: transparent;
  color: rgba(0,0,0,0.16);
  font-size: 2rem;
  line-height: 1;
  padding: 0;
}
.wheel-arrow.up { top: 10px; left: 50%; transform: translateX(-50%); }
.wheel-arrow.down { bottom: 8px; left: 50%; transform: translateX(-50%) rotate(180deg); }
.wheel-arrow.left { left: 14px; top: 50%; transform: translateY(-50%) rotate(-90deg); }
.wheel-arrow.right { right: 14px; top: 50%; transform: translateY(-50%) rotate(90deg); }
.bottom-toolbar {
  margin-top: 18px;
  display: grid;
  grid-template-columns: 44px 1fr 44px;
  gap: 16px;
  align-items: center;
}
.toolbar-pill,
.toolbar-arrow {
  height: 44px;
  border-radius: 999px;
  border: 0;
  background:
    radial-gradient(circle at 35% 35%, rgba(255,255,255,0.96), rgba(242,242,242,0.9) 40%, rgba(214,214,214,0.95) 100%);
  box-shadow: inset 0 1px 2px rgba(255,255,255,0.95), 0 1px 3px rgba(0,0,0,0.14);
}
.toolbar-pill {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 16px;
  font-size: 0.88rem;
}
.toolbar-arrow {
  font-size: 1.45rem;
}
.usb-icon {
  width: 20px;
  height: 20px;
  border: 2px solid #222;
  border-radius: 4px;
  position: relative;
}
.usb-icon::before {
  content: "";
  position: absolute;
  right: -8px;
  top: 6px;
  width: 8px;
  height: 2px;
  background: #222;
}
.screen-menu,
.screen-gallery {
  position: absolute;
  inset: 14px;
  background: var(--screen-bg, linear-gradient(180deg, #eef8ff 0%, #bfeef8 100%));
  display: none;
  padding: 10px 10px 14px;
}
.screen-menu.active,
.screen-gallery.active {
  display: block;
}
.menu-panel {
  background: rgba(255,255,255,0.62);
  border-radius: 4px;
  min-height: 100%;
  padding: 10px 10px 14px;
}
.menu-heading {
  font-size: 0.95rem;
  margin-bottom: 8px;
}
.menu-list {
  border-top: 2px solid rgba(0,0,0,0.55);
  border-bottom: 2px solid rgba(0,0,0,0.55);
}
.menu-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 8px;
  font-size: 0.95rem;
  border-bottom: 2px solid rgba(0,0,0,0.42);
}
.menu-row:last-child {
  border-bottom: 0;
}
.menu-row.active {
  background: linear-gradient(180deg, #fff8bf 0%, #ffe97a 100%);
  box-shadow: inset 0 0 0 1px rgba(0,0,0,0.2);
}
.subtle {
  color: rgba(0,0,0,0.5);
}
.prompt-detail {
  margin-top: 14px;
  background: rgba(255,255,255,0.3);
  padding: 12px 10px;
  border-radius: 4px;
  min-height: 92px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.prompt-detail h3 {
  margin: 0;
  font-size: 1rem;
}
.prompt-body {
  font-size: 0.9rem;
  line-height: 1.35;
}
.gallery-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 10px;
  max-height: 194px;
  overflow-y: auto;
  padding-right: 4px;
}
.thumb-button {
  aspect-ratio: 1;
  border: 2px solid rgba(0,0,0,0.18);
  border-radius: 4px;
  padding: 0;
  background: rgba(255,255,255,0.48);
  overflow: hidden;
}
.thumb-button.active {
  border-color: #3a7adf;
  box-shadow: 0 0 0 2px rgba(58,122,223,0.18);
}
.thumb-button img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.gallery-actions,
.theme-grid,
.prompt-edit-grid {
  display: grid;
  gap: 10px;
}
.theme-grid {
  grid-template-columns: repeat(2, 1fr);
  margin-top: 10px;
}
.theme-button {
  height: 44px;
  border-radius: 4px;
  border: 2px solid rgba(0,0,0,0.16);
  background: #fff;
  color: #111;
  font: inherit;
}
.theme-button.active {
  border-color: #3a7adf;
}
.theme-aqua { background: linear-gradient(180deg, #eef8ff 0%, #bfeef8 100%); }
.theme-silver { background: linear-gradient(180deg, #f7f7f7 0%, #d7dde4 100%); }
.theme-lavender { background: linear-gradient(180deg, #f6f1ff 0%, #d8d0ff 100%); }
.theme-mint { background: linear-gradient(180deg, #f0fff8 0%, #c8f0df 100%); }
.theme-sunset { background: linear-gradient(180deg, #fff5ef 0%, #ffd7bf 100%); }
.screen input[type="text"],
.screen textarea {
  width: 100%;
  border: 2px solid rgba(0,0,0,0.18);
  border-radius: 4px;
  padding: 8px 10px;
  font: inherit;
  background: rgba(255,255,255,0.82);
  color: #111;
}
.screen textarea {
  min-height: 86px;
  resize: vertical;
}
.save-row {
  display: flex;
  gap: 10px;
}
.save-row button {
  flex: 1;
  border-radius: 4px;
  border: 2px solid rgba(0,0,0,0.16);
  background: linear-gradient(180deg, #fff8bf 0%, #ffe97a 100%);
  color: #111;
  padding: 10px 12px;
  font: inherit;
}
.save-row .secondary {
  background: rgba(255,255,255,0.7);
}
.hidden {
  display: none !important;
}
.legacy-shell {
  display: none;
}
input[type="text"] {
  width: 100%;
  border-radius: 14px;
  border: 1px solid var(--border);
  padding: 12px 14px;
  font: inherit;
  background: rgba(255, 255, 255, 0.02);
  color: var(--text);
}
textarea {
  width: 100%;
  min-height: 128px;
  border-radius: 16px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  padding: 14px 16px;
  font: inherit;
  color: var(--text);
  line-height: 1.5;
  transition: border-color 120ms ease, box-shadow 120ms ease, background 120ms ease;
}
select {
  width: 100%;
  border-radius: 14px;
  border: 1px solid var(--border);
  padding: 12px 14px;
  font: inherit;
  background: rgba(255, 255, 255, 0.02);
  color: var(--text);
}
input[type="range"] {
  width: 100%;
  accent-color: var(--green);
}
textarea:focus,
select:focus,
input[type="text"]:focus {
  outline: none;
  border-color: var(--green);
  box-shadow: 0 0 0 4px rgba(85, 235, 90, 0.1);
  background: rgba(255, 255, 255, 0.04);
}
button {
  background: var(--green);
  color: #050505;
  border: 1px solid var(--green);
  border-radius: 999px;
  padding: 13px 18px;
  font: inherit;
  cursor: pointer;
  font-weight: 700;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  transition: transform 120ms ease, opacity 120ms ease;
}
button:hover,
.ghost-button:hover {
  transform: translateY(-1px);
}
button:active,
.ghost-button:active {
  transform: translateY(0);
}
.muted {
  color: var(--text-soft);
}
.status {
  font-weight: 700;
}
code {
  background: rgba(255, 255, 255, 0.05);
  padding: 2px 7px;
  border-radius: 8px;
  font-size: 0.95em;
}
.topbar {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  margin: 0 0 18px;
  padding: 14px 18px;
  border: 1px solid var(--border);
  border-radius: 18px;
  background: rgba(12, 12, 15, 0.9);
  box-shadow: var(--shadow);
}
.brand {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.brand strong {
  color: var(--green);
  font-size: 0.92rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
.brand span {
  color: var(--text-soft);
  font-size: 0.8rem;
}
.nav-links {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.nav-links a {
  color: var(--text-soft);
  text-decoration: none;
  font-weight: 700;
  padding: 10px 12px;
  border-radius: 999px;
  border: 1px solid transparent;
  text-transform: uppercase;
  font-size: 0.82rem;
  letter-spacing: 0.08em;
}
.nav-links a.current {
  color: var(--text);
  border-color: var(--green);
  background: rgba(85, 235, 90, 0.08);
}
.topbar.compact {
  justify-content: flex-start;
}
.shell-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(300px, 0.9fr);
  gap: 16px;
}
.stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.hero {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.eyebrow {
  display: inline-block;
  width: fit-content;
  padding: 8px 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  color: var(--green);
  font-size: 0.8rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  font-weight: 700;
}
.hero-copy p {
  max-width: 56ch;
  color: var(--text-soft);
  line-height: 1.7;
}
.ascii-panel {
  display: grid;
  gap: 14px;
  min-height: 100%;
  align-content: start;
}
.ascii-label {
  color: var(--blue);
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.12em;
}
.ascii-art {
  margin: 0;
  padding: 16px;
  border-radius: 18px;
  border: 1px solid var(--border);
  background:
    linear-gradient(180deg, rgba(39, 122, 255, 0.08), transparent 40%),
    rgba(255, 255, 255, 0.02);
  color: var(--green);
  font-size: clamp(0.82rem, 1.5vw, 1rem);
  line-height: 1.4;
  overflow-x: auto;
}
.status-grid,
.control-grid,
.prompt-grid,
.resource-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.status-tile,
.resource-card,
.prompt-card {
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 16px;
  background: var(--surface-ghost);
}
.status-tile strong,
.resource-card strong {
  display: block;
  margin-bottom: 6px;
  color: var(--text-soft);
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.status-tile span,
.status-tile code,
.resource-card span,
.resource-card a {
  color: var(--text);
  line-height: 1.55;
  word-break: break-word;
}
.status-tile.is-error span {
  color: #ff8f8f;
}
.image-frame {
  display: block;
  width: 100%;
  border-radius: 18px;
  border: 1px solid var(--border);
  background: #000;
  aspect-ratio: 3 / 2;
  object-fit: contain;
}
.home-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(300px, 0.95fr);
  gap: 16px;
}
.home-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.home-copy {
  color: var(--text-soft);
  line-height: 1.7;
}
.prompt-title {
  margin-bottom: 12px;
}
.prompt-card textarea {
  min-height: 168px;
}
.image-meta {
  display: grid;
  gap: 12px;
}
.empty-state {
  min-height: 240px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  border: 1px dashed var(--border);
  color: var(--text-soft);
  background: rgba(255, 255, 255, 0.015);
  text-align: center;
  padding: 24px;
}
.actions-row,
.links-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.ghost-button {
  display: inline-block;
  background: transparent;
  color: var(--text);
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  padding: 11px 16px;
  text-decoration: none;
  font: inherit;
  cursor: pointer;
  text-transform: uppercase;
  font-size: 0.82rem;
  letter-spacing: 0.08em;
  font-weight: 700;
}
.section-copy {
  color: var(--text-soft);
  line-height: 1.65;
}
.article h1,
.article h2,
.article h3 {
  line-height: 1.15;
  letter-spacing: -0.04em;
}
.article h2 {
  margin-top: 30px;
}
.article h3 {
  margin-top: 20px;
}
.article p,
.article li {
  line-height: 1.75;
  color: var(--text);
}
.article ul,
.article ol {
  padding-left: 22px;
}
.article pre {
  overflow-x: auto;
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
}
.article pre code {
  background: transparent;
  padding: 0;
}
.tutorial-image {
  display: block;
  width: 100%;
  max-width: 720px;
  margin: 14px 0;
  border-radius: 16px;
  border: 1px solid var(--border);
}
.preview-frame {
  width: 100%;
  max-width: 480px;
  aspect-ratio: 4 / 3;
  object-fit: contain;
  border-radius: 18px;
  border: 1px solid var(--border);
  background: #000;
}
.range-grid {
  display: grid;
  grid-template-columns: minmax(120px, 180px) 1fr 56px;
  gap: 12px 16px;
  align-items: center;
}
.range-grid output {
  font-variant-numeric: tabular-nums;
  text-align: right;
  color: var(--blue);
}
a {
  color: var(--green);
}
img {
  max-width: 100%;
}
@media (max-width: 900px) {
  .reference-layout {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  main {
    padding: 8px 8px 24px;
  }
  .device {
    width: 100%;
    min-height: 760px;
    border-radius: 34px;
    padding-left: 14px;
    padding-right: 14px;
  }
  .wheel {
    width: 240px;
    height: 240px;
  }
  .wheel-center {
    inset: 84px;
  }
}
"""


def render_shell(title: str, body_html: str, current_path: str = "") -> bytes:
    theme = "aqua"
    if current_path == "/" and hasattr(render_shell, "_theme_override"):
        theme = str(getattr(render_shell, "_theme_override"))
    show_topbar = current_path != "/"
    theme_color = "#f7f7f4" if current_path == "/" and theme == "silver" else "#050505"
    status_bar_style = "default" if current_path == "/" and theme == "silver" else "black-translucent"
    shell_style = "" if current_path == "/" and theme == "silver" else PAGE_STYLE
    page = f"""
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="theme-color" content="{theme_color}">
        <meta name="apple-mobile-web-app-capable" content="yes">
        <meta name="apple-mobile-web-app-status-bar-style" content="{status_bar_style}">
        <meta name="apple-mobile-web-app-title" content="ImageGenCam">
        <meta name="mobile-web-app-capable" content="yes">
        <title>{html.escape(title)}</title>
        <link rel="manifest" href="/manifest.webmanifest">
        <link rel="apple-touch-icon" href="/app-icon.png">
        <link rel="icon" type="image/png" href="/app-icon.png">
        <style>{shell_style}</style>
        <script>
          if ("serviceWorker" in navigator) {{
            window.addEventListener("load", () => {{
              navigator.serviceWorker.register("/service-worker.js").catch(() => {{}});
            }});
          }}
        </script>
      </head>
      <body data-theme="{html.escape(theme)}">
        <main>
          {"<div class='topbar'><div class='brand'><strong>[ImageGenCam]</strong><span>Local control surface for ImageGenCam.</span></div></div>" if show_topbar else ""}
          {body_html}
        </main>
      </body>
    </html>
    """
    return page.encode("utf-8")

def build_generated_image_list(controller) -> list[dict[str, object]]:
    generated_root = controller.project_root / "data" / "generated"
    items: list[dict[str, object]] = []
    for candidate in sorted(
        (path for path in generated_root.rglob("*") if is_generated_image_file(path)),
        key=lambda path: path.stat().st_mtime_ns,
        reverse=True,
    ):
        relative_path = candidate.relative_to(generated_root).as_posix()
        stat = candidate.stat()
        items.append(
            {
                "filename": candidate.name,
                "relative_path": relative_path,
                "image_url": f"/generated/{quote(relative_path)}",
                "download_url": f"/download/generated/{quote(relative_path)}",
                "modified_unix": stat.st_mtime,
                "size_bytes": stat.st_size,
            }
        )
    return items


def build_generated_images_zip(controller) -> bytes:
    generated_root = controller.project_root / "data" / "generated"
    output = io.BytesIO()
    with zipfile.ZipFile(output, mode="w", compression=zipfile.ZIP_DEFLATED) as archive:
        for candidate in sorted(
            (path for path in generated_root.rglob("*") if is_generated_image_file(path)),
            key=lambda path: path.stat().st_mtime_ns,
            reverse=True,
        ):
            archive.write(candidate, candidate.relative_to(generated_root).as_posix())
    return output.getvalue()


def build_device_details(controller) -> dict[str, object]:
    if hasattr(controller, "get_device_details"):
        try:
            details = controller.get_device_details()
        except Exception:
            logger.exception("Failed to read device details")
        else:
            if isinstance(details, dict):
                return details
    return {
        "battery_status": "Unknown",
        "wifi_network": "Unknown",
        "ip_address": "Unknown",
        "mac_address": "Unknown",
        "hostname": "Unknown",
        "app_url": "Unknown",
        "storage_status": "Unknown",
        "cpu_status": "Unknown",
    }


def delete_generated_image_by_relative_path(controller, relative_path: str) -> bool:
    if hasattr(controller, "delete_generated_image"):
        try:
            return bool(controller.delete_generated_image(relative_path))
        except Exception:
            logger.exception("Failed to delete generated image %s", relative_path)
            return False

    image_path = get_generated_image_by_relative_path(controller, relative_path)
    if image_path is None:
        return False
    try:
        image_path.unlink()
    except OSError:
        logger.exception("Failed to delete generated image %s", image_path)
        return False
    metadata_path = Path(f"{image_path}.json")
    try:
        metadata_path.unlink()
    except FileNotFoundError:
        pass
    except OSError:
        logger.warning("Failed to delete generated image metadata %s", metadata_path)
    return True


def is_capture_image_file(path: Path) -> bool:
    return (
        path.is_file()
        and not path.name.startswith(".")
        and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}
    )


def get_capture_image_by_relative_path(controller, relative_path: str) -> Path | None:
    capture_root = controller.project_root / "data" / "captures"
    decoded_relative = unquote(relative_path).lstrip("/")
    candidate = (capture_root / decoded_relative).resolve()
    try:
        candidate.relative_to(capture_root.resolve())
    except ValueError:
        return None
    if is_capture_image_file(candidate):
        return candidate
    return None


def build_magic_history_list(controller) -> list[dict[str, object]]:
    items: list[dict[str, object]] = []
    for entry in controller.get_magic_history_entries():
        reference_capture_path = entry.get("reference_capture_path")
        reference_url = None
        if isinstance(reference_capture_path, str) and reference_capture_path:
            reference_path = Path(reference_capture_path)
            if not reference_path.is_absolute():
                capture_root = controller.project_root / "data" / "captures"
                try:
                    reference_relative = reference_path.relative_to(capture_root.relative_to(controller.project_root))
                except ValueError:
                    reference_relative = reference_path
                reference_url = f"/captures/{quote(reference_relative.as_posix())}"
            else:
                try:
                    reference_relative = reference_path.relative_to(
                        (controller.project_root / "data" / "captures").resolve()
                    )
                except ValueError:
                    reference_relative = None
                if reference_relative is not None:
                    reference_url = f"/captures/{quote(reference_relative.as_posix())}"
        items.append(
            {
                "id": entry["id"],
                "created_at": entry["created_at"],
                "title": entry["title"],
                "body": entry["body"],
                "reference_image_url": reference_url,
                "promoted_prompt_id": entry.get("promoted_prompt_id"),
            }
        )
    return items


def render_page(controller, message: str = "") -> bytes:
    prompt_payload = json_for_inline_script(controller.get_prompt_entries())
    image_payload = json_for_inline_script([])
    details_payload = json_for_inline_script(build_device_details(controller))
    message_html = f"<p class='notice'>{html.escape(message)}</p>" if message else ""

    setattr(render_shell, "_theme_override", "silver")

    body = """
      <style>
        :root {
          --bg:#f4f4ef;
          --fg:#111;
          --muted:#6d6a63;
          --line:#d8d5cc;
          --card:#fffefa;
          --font:ui-sans-serif,-apple-system,BlinkMacSystemFont,"SF Pro Text","Helvetica Neue",Arial,sans-serif;
        }
        body {
          background:var(--bg);
          color:var(--fg);
          font-family:var(--font);
          font-size:16px;
          line-height:1.45;
        }
        main { padding:0; }
        .app {
          min-height:100vh;
          max-width:980px;
          margin:0 auto;
          padding:18px;
          font-family:var(--font);
        }
        .bar {
          display:flex;
          align-items:center;
          justify-content:space-between;
          gap:20px;
          padding:10px 0 70px;
        }
        .brand { margin:0; font-size:18px; line-height:1.2; font-weight:600; }
        .nav { display:flex; gap:18px; flex-wrap:wrap; justify-content:flex-end; }
        .nav button {
          appearance:none;
          border:0;
          background:transparent;
          padding:0;
          color:var(--fg);
          font-family:var(--font);
          font-size:14px;
          line-height:1.2;
          font-weight:500;
          cursor:pointer;
        }
        .nav button.active { text-decoration:underline; text-underline-offset:5px; }
        .notice { margin:0 0 24px; color:var(--muted); }
        .panel { display:none; border-top:1px solid var(--line); padding-top:18px; }
        .panel.active { display:block; }
        .section-title {
          display:flex;
          align-items:center;
          justify-content:space-between;
          gap:16px;
          margin-bottom:24px;
        }
        .section-title h3 {
          margin:0;
          font-size:28px;
          line-height:1.15;
          font-weight:600;
        }
        .status { margin:0 0 14px; color:var(--muted); font-size:14px; line-height:1.4; }
        .status:empty { display:none; }
        .button-row { display:flex; gap:8px; flex-wrap:wrap; }
        .action {
          appearance:none;
          border:1px solid var(--line);
          border-radius:20px;
          background:transparent;
          color:var(--fg);
          padding:8px 13px;
          font-family:var(--font);
          font-size:14px;
          line-height:1.2;
          font-weight:500;
        }
        .action.primary { background:var(--fg); color:var(--bg); border-color:var(--fg); }
        .action:disabled { opacity:.42; cursor:not-allowed; }
        .prompt-list { display:grid; gap:12px; }
        .prompt-card {
          display:grid;
          gap:10px;
          padding:14px 0 18px;
          border-bottom:1px solid var(--line);
        }
        .prompt-card-head {
          display:flex;
          align-items:center;
          justify-content:space-between;
          gap:12px;
          font-size:14px;
          line-height:1.4;
          font-weight:500;
          color:var(--muted);
        }
        input, textarea {
          width:100%;
          border:1px solid var(--line);
          border-radius:10px;
          background:var(--card);
          color:var(--fg);
          padding:12px;
          font-family:var(--font);
          font-size:16px;
          line-height:1.4;
          font-weight:400;
        }
        textarea { min-height:120px; resize:vertical; line-height:1.45; }
        .gallery { display:grid; gap:16px; }
        .gallery-actions { margin-top:-4px; }
        .image-stage {
          min-height:220px;
          border:1px solid var(--line);
          background:var(--card);
          display:grid;
          place-items:center;
          overflow:hidden;
        }
        .image-stage img { width:100%; max-height:58vh; object-fit:contain; display:block; }
        .gallery-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; }
        .thumb {
          aspect-ratio:1;
          padding:0;
          border:1px solid var(--line);
          background:var(--card);
          font-family:var(--font);
          overflow:hidden;
        }
        .thumb.active { border-color:var(--fg); }
        .thumb img { width:100%; height:100%; object-fit:cover; display:block; }
        .empty { color:var(--muted); padding:40px 16px; text-align:center; }
        .details-grid { border-top:1px solid var(--line); }
        .detail-row {
          display:grid;
          grid-template-columns:minmax(112px, .34fr) 1fr;
          gap:18px;
          padding:14px 0;
          border-bottom:1px solid var(--line);
        }
        .detail-label { color:var(--muted); font-size:14px; line-height:1.4; }
        .detail-value { color:var(--fg); font-size:16px; line-height:1.4; word-break:break-word; }
        @media (max-width:640px) {
          .app { padding:14px; }
          .bar { align-items:flex-start; padding-bottom:56px; }
          .nav { gap:12px; }
          .section-title { align-items:flex-start; }
          .gallery-grid { grid-template-columns:repeat(2,minmax(0,1fr)); }
          .detail-row { grid-template-columns:1fr; gap:4px; }
        }
      </style>

      <section class="app">
        __MESSAGE_HTML__
        <header class="bar">
          <h1 class="brand">ImageGenCam</h1>
          <nav class="nav" aria-label="App sections">
            <button class="active" type="button" data-tab="prompt">Prompts</button>
            <button type="button" data-tab="gallery">Gallery</button>
            <button type="button" data-tab="about">About</button>
          </nav>
        </header>

        <section class="panel active" id="panel-prompt">
          <div class="section-title">
            <h3>Prompts</h3>
            <div class="button-row">
              <button class="action" id="add-prompt-button" type="button">Add</button>
            </div>
          </div>
          <p class="status" id="prompt-status" aria-live="polite"></p>
          <div class="prompt-list" id="prompt-list"></div>
        </section>

        <section class="panel" id="panel-gallery">
          <div class="section-title">
            <h3>Gallery</h3>
            <button class="action primary" id="download-all-button" type="button">Download All</button>
          </div>
          <p class="status" id="gallery-count">0 images</p>
          <div class="gallery">
            <div class="image-stage" id="image-stage"></div>
            <div class="button-row gallery-actions">
              <button class="action" id="download-selected-button" type="button">Download Selected</button>
              <button class="action" id="delete-selected-button" type="button">Delete Selected</button>
            </div>
            <p class="status" id="gallery-status" aria-live="polite"></p>
            <div class="gallery-grid" id="gallery-grid"></div>
          </div>
        </section>

        <section class="panel" id="panel-about">
          <div class="section-title"><h3>About</h3></div>
          <div class="details-grid" id="device-details"></div>
        </section>
      </section>

      <script>
        const PROMPT_TITLE_MAX_LENGTH = __PROMPT_TITLE_MAX_LENGTH__;
        let promptEntries = __PROMPT_PAYLOAD__;
        let images = __IMAGE_PAYLOAD__;
        let deviceDetails = __DETAILS_PAYLOAD__;
        let currentIndex = 0;
        let promptSaveTimer = null;
        let promptSaveGeneration = 0;

        const promptList = document.getElementById("prompt-list");
        const promptStatus = document.getElementById("prompt-status");
        const imageStage = document.getElementById("image-stage");
        const galleryGrid = document.getElementById("gallery-grid");
        const galleryCount = document.getElementById("gallery-count");
        const galleryStatus = document.getElementById("gallery-status");
        const downloadAllButton = document.getElementById("download-all-button");
        const downloadSelectedButton = document.getElementById("download-selected-button");
        const deleteSelectedButton = document.getElementById("delete-selected-button");
        const deviceDetailsElement = document.getElementById("device-details");

        function selectTab(name) {
          document.querySelectorAll(".nav button").forEach((button) => {
            button.classList.toggle("active", button.dataset.tab === name);
          });
          document.querySelectorAll(".panel").forEach((panel) => {
            panel.classList.toggle("active", panel.id === `panel-${name}`);
          });
          history.replaceState(null, "", `#${name}`);
        }

        function currentImage() {
          return images[currentIndex] || null;
        }

        function renderPrompts() {
          promptList.innerHTML = "";
          promptEntries.forEach((entry, index) => {
            const card = document.createElement("article");
            card.className = "prompt-card";
            card.dataset.promptId = entry.id || "";
            card.innerHTML = `
              <div class="prompt-card-head">
                <span>Prompt ${index + 1}</span>
                <button type="button" class="action remove-prompt-button" ${promptEntries.length <= 1 ? "disabled" : ""}>Remove</button>
              </div>
              <input class="prompt-title-field" type="text" maxlength="${PROMPT_TITLE_MAX_LENGTH}" placeholder="Title" value="">
              <textarea class="prompt-body-field" placeholder="Prompt body"></textarea>
            `;
            card.querySelector(".prompt-title-field").value = entry.title || "";
            card.querySelector(".prompt-body-field").value = entry.body || "";
            card.querySelector(".prompt-title-field").addEventListener("input", schedulePromptSave);
            card.querySelector(".prompt-body-field").addEventListener("input", schedulePromptSave);
            card.querySelector(".remove-prompt-button").addEventListener("click", () => {
              if (promptEntries.length <= 1) return;
              promptEntries.splice(index, 1);
              renderPrompts();
              schedulePromptSave();
            });
            promptList.appendChild(card);
          });
        }

        function promptsFromDom() {
          return Array.from(promptList.querySelectorAll(".prompt-card")).map((card, index) => ({
            id: card.dataset.promptId || `prompt-${Date.now()}-${index}`,
            title: card.querySelector(".prompt-title-field").value.trim().slice(0, PROMPT_TITLE_MAX_LENGTH) || "New Prompt",
            body: card.querySelector(".prompt-body-field").value.trim() || "Describe the edit you want.",
          }));
        }

        function syncPromptIds(savedEntries) {
          if (!Array.isArray(savedEntries)) return;
          const cards = Array.from(promptList.querySelectorAll(".prompt-card"));
          cards.forEach((card, index) => {
            if (savedEntries[index] && savedEntries[index].id) {
              card.dataset.promptId = savedEntries[index].id;
            }
          });
        }

        function schedulePromptSave() {
          promptStatus.textContent = "Saving...";
          if (promptSaveTimer) clearTimeout(promptSaveTimer);
          promptSaveTimer = setTimeout(() => {
            savePrompts().catch(() => {
              promptStatus.textContent = "Save failed.";
            });
          }, 500);
        }

        async function savePrompts() {
          const saveGeneration = ++promptSaveGeneration;
          promptStatus.textContent = "Saving...";
          promptEntries = promptsFromDom();
          const response = await fetch("/save", {
            method: "POST",
            headers: { "Content-Type": "application/json;charset=UTF-8" },
            body: JSON.stringify({ prompts: promptEntries }),
          });
          if (!response.ok) {
            promptStatus.textContent = "Save failed.";
            return;
          }
          const data = await response.json();
          if (saveGeneration !== promptSaveGeneration) return;
          promptEntries = data.prompt_entries || promptEntries;
          promptStatus.textContent = "Saved.";
          syncPromptIds(promptEntries);
        }

        function renderGallery() {
          const image = currentImage();
          const hasImages = images.length > 0;
          galleryCount.textContent = `${images.length} image${images.length === 1 ? "" : "s"}`;
          downloadAllButton.disabled = !hasImages;
          downloadSelectedButton.disabled = !image;
          deleteSelectedButton.disabled = !image;
          imageStage.innerHTML = image
            ? `<img src="${image.image_url}" alt="${image.filename}">`
            : '<div class="empty">No generated images yet.</div>';
          galleryGrid.innerHTML = images.length ? "" : '<div class="empty">Take a photo to add one.</div>';
          images.forEach((item, index) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = `thumb ${index === currentIndex ? "active" : ""}`;
            button.innerHTML = `<img src="${item.image_url}" alt="${item.filename}">`;
            button.addEventListener("click", () => {
              currentIndex = index;
              renderGallery();
            });
            galleryGrid.appendChild(button);
          });
        }

        async function refreshImages() {
          const response = await fetch("/api/images", { cache: "no-store" });
          if (!response.ok) return;
          const previous = currentImage()?.filename || null;
          const data = await response.json();
          images = data.images || [];
          const matchedIndex = images.findIndex((item) => item.filename === previous);
          currentIndex = matchedIndex >= 0 ? matchedIndex : 0;
          renderGallery();
        }

        function downloadCurrentImage() {
          const image = currentImage();
          if (!image) return;
          const link = document.createElement("a");
          link.href = image.download_url || image.image_url;
          link.download = image.filename || "image.jpg";
          document.body.appendChild(link);
          link.click();
          link.remove();
        }

        function downloadAllImages() {
          if (!images.length) return;
          const link = document.createElement("a");
          link.href = "/download/all";
          link.download = "imagegencam-images.zip";
          document.body.appendChild(link);
          link.click();
          link.remove();
        }

        async function deleteCurrentImage() {
          const image = currentImage();
          if (!image || !image.relative_path) return;
          if (!confirm("Delete the selected photo?")) return;
          galleryStatus.textContent = "Deleting...";
          const response = await fetch("/api/images/delete", {
            method: "POST",
            headers: { "Content-Type": "application/json;charset=UTF-8" },
            body: JSON.stringify({ relative_path: image.relative_path }),
          });
          if (!response.ok) {
            galleryStatus.textContent = "Delete failed.";
            return;
          }
          const data = await response.json();
          images = data.images || [];
          if (currentIndex >= images.length) {
            currentIndex = Math.max(0, images.length - 1);
          }
          galleryStatus.textContent = "Deleted.";
          renderGallery();
        }

        function renderDeviceDetails() {
          const rows = [
            ["Battery", deviceDetails.battery_status],
            ["Wi-Fi", deviceDetails.wifi_network],
            ["IP Address", deviceDetails.ip_address],
            ["MAC Address", deviceDetails.mac_address],
            ["Storage", deviceDetails.storage_status],
            ["CPU", deviceDetails.cpu_status],
            ["Hostname", deviceDetails.hostname],
            ["App URL", deviceDetails.app_url],
          ];
          deviceDetailsElement.innerHTML = "";
          rows.forEach(([label, value]) => {
            const row = document.createElement("div");
            row.className = "detail-row";
            const labelElement = document.createElement("div");
            labelElement.className = "detail-label";
            labelElement.textContent = label;
            const valueElement = document.createElement("div");
            valueElement.className = "detail-value";
            valueElement.textContent = value || "Unknown";
            row.append(labelElement, valueElement);
            deviceDetailsElement.appendChild(row);
          });
        }

        async function refreshDeviceDetails() {
          const response = await fetch("/api/device-details", { cache: "no-store" });
          if (!response.ok) return;
          deviceDetails = await response.json();
          renderDeviceDetails();
        }

        document.querySelectorAll(".nav button").forEach((button) => {
          button.addEventListener("click", () => selectTab(button.dataset.tab));
        });
        document.getElementById("add-prompt-button").addEventListener("click", () => {
          promptEntries.unshift({
            id: `prompt-${Date.now()}`,
            title: "New Prompt",
            body: "Describe the edit you want.",
          });
          renderPrompts();
          promptList.scrollIntoView({ block: "start", behavior: "smooth" });
          schedulePromptSave();
        });
        downloadAllButton.addEventListener("click", downloadAllImages);
        downloadSelectedButton.addEventListener("click", downloadCurrentImage);
        deleteSelectedButton.addEventListener("click", () => deleteCurrentImage().catch(() => {
          galleryStatus.textContent = "Delete failed.";
        }));

        const initialTab = ["prompt", "gallery", "about"].includes(location.hash.slice(1))
          ? location.hash.slice(1)
          : "prompt";
        renderPrompts();
        renderGallery();
        renderDeviceDetails();
        selectTab(initialTab);
        refreshImages().catch(() => {});
        refreshDeviceDetails().catch(() => {});
        setInterval(() => refreshImages().catch(() => {}), 5000);
        setInterval(() => refreshDeviceDetails().catch(() => {}), 5000);
      </script>
    """
    body = (
        body.replace("__MESSAGE_HTML__", message_html)
        .replace("__PROMPT_TITLE_MAX_LENGTH__", str(PROMPT_TITLE_MAX_LENGTH))
        .replace("__PROMPT_PAYLOAD__", prompt_payload)
        .replace("__IMAGE_PAYLOAD__", image_payload)
        .replace("__DETAILS_PAYLOAD__", details_payload)
    )
    return render_shell("ImageGenCam", body, current_path="/")


def get_repo_root(project_root: Path) -> Path:
    parent_readme = project_root.parent / "README.md"
    if parent_readme.exists():
        return project_root.parent
    return project_root


def build_manifest_bytes() -> bytes:
    manifest = {
        "name": "ImageGenCam",
        "short_name": "ImageGenCam",
        "start_url": "/",
        "scope": "/",
        "display": "standalone",
        "background_color": "#f7f7f4",
        "theme_color": "#f7f7f4",
        "description": "Local PWA controller for ImageGenCam.",
        "icons": [
            {
                "src": "/app-icon.png",
                "sizes": "512x512",
                "type": "image/png",
                "purpose": "any maskable",
            }
        ],
    }
    return json.dumps(manifest).encode("utf-8")


def build_service_worker_bytes() -> bytes:
    script = """
const CACHE_NAME = "imagegencam-pwa-v3";
const CORE_URLS = ["/", "/manifest.webmanifest", "/app-icon.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(CORE_URLS)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (
    url.pathname.startsWith("/api/") ||
    url.pathname.startsWith("/download/") ||
    url.pathname.startsWith("/generated/") ||
    url.pathname === "/latest-image"
  ) {
    event.respondWith(fetch(request));
    return;
  }
  if (url.pathname === "/") {
    event.respondWith(
      fetch(request).then((response) => {
        const cloned = response.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put("/", cloned));
        return response;
      }).catch(() => caches.match("/"))
    );
    return;
  }
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request).catch(() => caches.match(request).then((cached) => cached || caches.match("/")))
    );
    return;
  }
  event.respondWith(
    caches.match(request).then((cached) => cached || fetch(request).then((response) => {
      const cloned = response.clone();
      caches.open(CACHE_NAME).then((cache) => cache.put(request, cloned));
      return response;
    }))
  );
});
""".strip()
    return script.encode("utf-8")


def build_app_icon_bytes(project_root: Path) -> bytes:
    repo_root = get_repo_root(project_root)
    layout_path = repo_root / "docs" / "tutorial-assets" / "layout.png"
    icon = Image.new("RGB", (512, 512), (5, 5, 5))
    if layout_path.exists():
        with Image.open(layout_path) as source:
            source = source.convert("RGB")
            source.thumbnail((420, 420))
            offset = ((512 - source.width) // 2, (512 - source.height) // 2)
            icon.paste(source, offset)
    output = io.BytesIO()
    icon.save(output, format="PNG")
    return output.getvalue()


def read_project_asset(project_root: Path, request_path: str) -> tuple[bytes, str] | None:
    relative = request_path.lstrip("/")
    asset_path = (project_root / relative).resolve()
    try:
        asset_path.relative_to(project_root)
    except ValueError:
        return None
    if not asset_path.is_file():
        return None
    content_type = mimetypes.guess_type(asset_path.name)[0] or "application/octet-stream"
    return asset_path.read_bytes(), content_type

def is_generated_image_file(path: Path) -> bool:
    return (
        path.is_file()
        and not path.name.startswith(".")
        and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}
    )


def get_latest_generated_path(controller) -> Path | None:
    snapshot = controller.get_status_snapshot()
    path_value = snapshot["last_generated_path"]
    if not path_value:
        generated_root = controller.project_root / "data" / "generated"
        candidates = [path for path in generated_root.rglob("*") if is_generated_image_file(path)]
        if not candidates:
            return None
        return max(candidates, key=lambda candidate: candidate.stat().st_mtime_ns)
    path = Path(path_value)
    if is_generated_image_file(path):
        return path
    generated_root = controller.project_root / "data" / "generated"
    candidates = [
        candidate for candidate in generated_root.rglob("*") if is_generated_image_file(candidate)
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda candidate: candidate.stat().st_mtime_ns)


def build_latest_generated_metadata(controller) -> dict[str, object]:
    path = get_latest_generated_path(controller)
    if path is None:
        return {
            "available": False,
            "image_url": "/latest-image",
            "filename": None,
            "content_type": None,
            "size_bytes": None,
            "modified_unix": None,
            "etag": None,
        }

    stat = path.stat()
    content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    etag = f'"{path.name}-{stat.st_mtime_ns}-{stat.st_size}"'
    return {
        "available": True,
        "image_url": "/latest-image",
        "filename": path.name,
        "content_type": content_type,
        "size_bytes": stat.st_size,
        "modified_unix": stat.st_mtime,
        "etag": etag,
    }


def get_generated_image_by_relative_path(controller, relative_path: str) -> Path | None:
    generated_root = controller.project_root / "data" / "generated"
    decoded_relative = unquote(relative_path).lstrip("/")
    candidate = (generated_root / decoded_relative).resolve()
    try:
        candidate.relative_to(generated_root.resolve())
    except ValueError:
        return None
    if is_generated_image_file(candidate):
        return candidate
    return None


def build_handler(controller):
    class PromptHandler(BaseHTTPRequestHandler):
        def _read_request_body(self) -> bytes | None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self.send_error(HTTPStatus.BAD_REQUEST, "Invalid Content-Length")
                return None
            if length > MAX_POST_BODY_BYTES:
                self.send_error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large")
                return None
            return self.rfile.read(length)

        def _read_json_body(self) -> dict[str, object] | None:
            raw_body = self._read_request_body()
            if raw_body is None:
                return None
            try:
                payload = json.loads(raw_body.decode("utf-8") or "{}")
            except (UnicodeDecodeError, json.JSONDecodeError):
                self.send_error(HTTPStatus.BAD_REQUEST, "Invalid JSON")
                return None
            if not isinstance(payload, dict):
                self.send_error(HTTPStatus.BAD_REQUEST, "JSON body must be an object")
                return None
            return payload

        def _send_json(self, payload: dict[str, object], status: HTTPStatus = HTTPStatus.OK) -> None:
            response = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def _serve_image_path(self, image_path: Path, *, as_attachment: bool = False) -> None:
            body = image_path.read_bytes()
            content_type = mimetypes.guess_type(image_path.name)[0] or "application/octet-stream"
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            if as_attachment:
                self.send_header("Content-Disposition", f'attachment; filename="{image_path.name}"')
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                return

        def _serve_latest_generated_image(self, include_body: bool = True) -> None:
            path = get_latest_generated_path(controller)
            if path is None:
                self.send_error(HTTPStatus.NOT_FOUND, "No generated image available yet")
                return

            stat = path.stat()
            content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            etag = f'"{path.name}-{stat.st_mtime_ns}-{stat.st_size}"'
            if self.headers.get("If-None-Match") == etag:
                self.send_response(HTTPStatus.NOT_MODIFIED)
                self.send_header("ETag", etag)
                self.end_headers()
                return

            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(stat.st_size))
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            self.send_header("ETag", etag)
            self.send_header("Last-Modified", formatdate(stat.st_mtime, usegmt=True))
            self.end_headers()
            if include_body:
                try:
                    self.wfile.write(path.read_bytes())
                except (BrokenPipeError, ConnectionResetError):
                    return

        def _serve_screen_preview(self) -> None:
            body = controller.get_screen_preview_jpeg()
            if body is None:
                self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "Screen preview unavailable")
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                return

        def do_GET(self) -> None:
            request_path = self.path.split("?", 1)[0]
            if request_path == "/download/all":
                body = build_generated_images_zip(controller)
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Content-Disposition", 'attachment; filename="imagegencam-images.zip"')
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path.startswith("/download/generated/"):
                relative_path = request_path[len("/download/generated/") :]
                image_path = get_generated_image_by_relative_path(controller, relative_path)
                if image_path is None:
                    self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
                    return
                self._serve_image_path(image_path, as_attachment=True)
                return
            if request_path.startswith("/generated/"):
                relative_path = request_path[len("/generated/") :]
                image_path = get_generated_image_by_relative_path(controller, relative_path)
                if image_path is None:
                    self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
                    return
                self._serve_image_path(image_path)
                return
            if request_path.startswith("/captures/"):
                relative_path = request_path[len("/captures/") :]
                image_path = get_capture_image_by_relative_path(controller, relative_path)
                if image_path is None:
                    self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
                    return
                self._serve_image_path(image_path)
                return
            if request_path in {"/screen-preview.jpg", "/screen-preview.png"}:
                self._serve_screen_preview()
                return
            if request_path == "/manifest.webmanifest":
                body = build_manifest_bytes()
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/manifest+json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path == "/service-worker.js":
                body = build_service_worker_bytes()
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/javascript; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path in {"/app-icon.png", "/favicon.png"}:
                body = build_app_icon_bytes(controller.project_root)
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "image/png")
                self.send_header("Cache-Control", "public, max-age=3600")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path.startswith("/assets/"):
                asset = read_project_asset(controller.project_root, request_path)
                if asset is None:
                    self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
                    return
                body, content_type = asset
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", content_type)
                self.send_header("Cache-Control", "public, max-age=3600")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path == "/latest-image":
                self._serve_latest_generated_image(include_body=True)
                return
            if request_path == "/api/latest-image":
                body = json.dumps(build_latest_generated_metadata(controller)).encode("utf-8")
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path == "/api/images":
                body = json.dumps({"images": build_generated_image_list(controller)}).encode("utf-8")
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path == "/api/device-details":
                body = json.dumps(build_device_details(controller)).encode("utf-8")
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path == "/api/magic-history":
                body = json.dumps({"magic_history": build_magic_history_list(controller)}).encode("utf-8")
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if request_path != "/":
                self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
                return
            body = render_page(controller)
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self) -> None:
            if self.path == "/settings/profile":
                payload = self._read_json_body()
                if payload is None:
                    return
                cleaned_username = controller.update_camera_username(
                    str(payload.get("camera_username") or "")
                )
                self._send_json({"camera_username": cleaned_username})
                return
            if self.path == "/settings/theme":
                raw_body = self._read_request_body()
                if raw_body is None:
                    return
                body = raw_body.decode("utf-8", errors="replace")
                payload = parse_qs(body, keep_blank_values=True)
                theme = payload.get("app_background_theme", ["aqua"])[0]
                cleaned_theme = controller.update_app_background_theme(theme)
                self._send_json({"app_background_theme": cleaned_theme})
                return
            if self.path == "/api/magic-history/promote":
                payload = self._read_json_body()
                if payload is None:
                    return
                entry_id = str(payload.get("entry_id") or "").strip()
                prompt_id = str(payload.get("prompt_id") or "").strip()
                if not entry_id:
                    self.send_error(HTTPStatus.BAD_REQUEST, "Missing entry_id")
                    return
                magic_history = controller.mark_magic_history_promoted(entry_id, prompt_id)
                self._send_json({"ok": True, "magic_history": build_magic_history_list(controller)})
                return
            if self.path == "/api/images/delete":
                payload = self._read_json_body()
                if payload is None:
                    return
                relative_path = str(payload.get("relative_path") or "").strip()
                if not relative_path:
                    self.send_error(HTTPStatus.BAD_REQUEST, "Missing relative_path")
                    return
                if not delete_generated_image_by_relative_path(controller, relative_path):
                    self.send_error(HTTPStatus.NOT_FOUND, "Generated image not found")
                    return
                self._send_json({"ok": True, "images": build_generated_image_list(controller)})
                return
            if self.path == "/api/recreate-vertical":
                payload = self._read_json_body()
                if payload is None:
                    return
                relative_path = str(payload.get("relative_path") or "").strip()
                if not relative_path:
                    self.send_error(HTTPStatus.BAD_REQUEST, "Missing relative_path")
                    return
                try:
                    image = controller.recreate_vertical_from_generated(relative_path)
                except FileNotFoundError:
                    self.send_error(HTTPStatus.NOT_FOUND, "Generated image not found")
                    return
                except ValueError as exc:
                    self.send_error(HTTPStatus.BAD_REQUEST, str(exc))
                    return
                except Exception as exc:
                    logger.exception("Vertical recreate failed for %s", relative_path)
                    self.send_error(HTTPStatus.BAD_GATEWAY, f"Vertical recreate failed: {exc}")
                    return
                self._send_json({"ok": True, "image": image})
                return
            if self.path != "/save":
                self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
                return
            raw_body = self._read_request_body()
            if raw_body is None:
                return
            content_type = self.headers.get("Content-Type", "")
            if "application/json" in content_type:
                try:
                    payload = json.loads(raw_body.decode("utf-8") or "{}")
                except (UnicodeDecodeError, json.JSONDecodeError):
                    self.send_error(HTTPStatus.BAD_REQUEST, "Invalid JSON")
                    return
                prompt_entries = payload.get("prompts", [])
            else:
                body = raw_body.decode("utf-8")
                payload = parse_qs(body, keep_blank_values=True)
                prompt_entries = []
                for key, values in payload.items():
                    if not key.startswith("prompt_body_"):
                        continue
                    prompt_id = key[len("prompt_body_") :]
                    prompt_entries.append(
                        {
                            "id": prompt_id,
                            "title": payload.get(f"prompt_title_{prompt_id}", [""])[0],
                            "body": values[0],
                        }
                    )
            cleaned_entries = controller.update_prompt_entries(prompt_entries)
            self._send_json({"ok": True, "prompt_entries": cleaned_entries})

        def do_HEAD(self) -> None:
            request_path = self.path.split("?", 1)[0]
            if request_path == "/download/all":
                body = build_generated_images_zip(controller)
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Content-Disposition", 'attachment; filename="imagegencam-images.zip"')
                self.end_headers()
                return
            if request_path.startswith("/download/generated/"):
                relative_path = request_path[len("/download/generated/") :]
                image_path = get_generated_image_by_relative_path(controller, relative_path)
                if image_path is None:
                    self.send_error(HTTPStatus.NOT_FOUND, "Not Found")
                    return
                stat = image_path.stat()
                content_type = mimetypes.guess_type(image_path.name)[0] or "application/octet-stream"
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", content_type)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(stat.st_size))
                self.send_header("Content-Disposition", f'attachment; filename="{image_path.name}"')
                self.end_headers()
                return
            if request_path in {"/screen-preview.jpg", "/screen-preview.png"}:
                body = controller.get_screen_preview_jpeg()
                if body is None:
                    self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "Screen preview unavailable")
                    return
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                return
            if request_path == "/latest-image":
                self._serve_latest_generated_image(include_body=False)
                return
            if request_path == "/":
                body = render_page(controller)
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                return
            self.send_error(HTTPStatus.NOT_FOUND, "Not Found")

        def log_message(self, format: str, *args) -> None:
            return

    return PromptHandler


class WebServerThread(Thread):
    def __init__(self, controller, host: str, port: int) -> None:
        super().__init__(daemon=True)
        self.server = ThreadingHTTPServer((host, port), build_handler(controller))

    def run(self) -> None:
        self.server.serve_forever(poll_interval=0.5)

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()
