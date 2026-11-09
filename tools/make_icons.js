// 【已废弃 / legacy】v1.7.2 起品牌图统一由 tools/make_logo.java 从
// app/src/main/assets/logo.png 派生（启动图标 5 档 + TV 横幅 3 档）。
// 请勿再运行本脚本，它会用旧的「代码绘制书本图标」覆盖新 logo。
// 保留仅作历史参考：手写 PNG 编码（无第三方依赖）的实现示例。
// 生成启动图标（无第三方依赖，手写 PNG 编码）
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

function crc32(buf) {
  let c, table = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii');
  const body = Buffer.concat([t, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function encodePng(w, h, pixels) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    pixels.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function inRoundRect(x, y, x0, y0, x1, y1, r) {
  if (x < x0 || y < y0 || x > x1 || y > y1) return false;
  const cx = Math.min(Math.max(x, x0 + r), x1 - r);
  const cy = Math.min(Math.max(y, y0 + r), y1 - r);
  const dx = x - cx, dy = y - cy;
  return dx * dx + dy * dy <= r * r;
}

function lerp(a, b, t) { return a + (b - a) * t; }

function draw(size) {
  const px = Buffer.alloc(size * size * 4);
  const S = size;
  const set = (x, y, r, g, b, a) => {
    if (x < 0 || y < 0 || x >= S || y >= S) return;
    const i = (y * S + x) * 4;
    const oa = a / 255, ba = px[i + 3] / 255;
    const na = oa + ba * (1 - oa);
    if (na <= 0) return;
    px[i] = Math.round((r * oa + px[i] * ba * (1 - oa)) / na);
    px[i + 1] = Math.round((g * oa + px[i + 1] * ba * (1 - oa)) / na);
    px[i + 2] = Math.round((b * oa + px[i + 2] * ba * (1 - oa)) / na);
    px[i + 3] = Math.round(na * 255);
  };

  const r = S * 0.23;
  // 背景：深蓝渐变圆角方块
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      if (!inRoundRect(x, y, 0, 0, S - 1, S - 1, r)) continue;
      const t = y / S;
      set(x, y, Math.round(lerp(32, 11, t)), Math.round(lerp(95, 18, t)), Math.round(lerp(168, 32, t)), 255);
    }
  }

  // 白色书本
  const bw = S * 0.56, bh = S * 0.38;
  const bx0 = (S - bw) / 2, by0 = S * 0.31, bx1 = bx0 + bw, by1 = by0 + bh;
  const br = S * 0.05;
  for (let y = Math.floor(by0); y <= Math.ceil(by1); y++) {
    for (let x = Math.floor(bx0); x <= Math.ceil(bx1); x++) {
      if (inRoundRect(x, y, bx0, by0, bx1, by1, br)) set(x, y, 245, 250, 255, 255);
    }
  }
  // 青色书脊
  const sx0 = bx0 + bw * 0.44, sx1 = sx0 + S * 0.035;
  for (let y = Math.floor(by0); y <= Math.ceil(by1); y++) {
    for (let x = Math.floor(sx0); x <= Math.ceil(sx1); x++) {
      set(x, y, 76, 194, 255, 255);
    }
  }
  // 右侧文字线
  const lines = [0.28, 0.5, 0.72];
  for (const ly of lines) {
    const y0 = by0 + bh * ly;
    const xa = sx1 + S * 0.05, xb = bx1 - S * 0.06;
    const hgt = Math.max(1, Math.round(S * 0.045));
    for (let y = Math.floor(y0); y < Math.floor(y0) + hgt; y++) {
      for (let x = Math.floor(xa); x <= Math.ceil(xb); x++) {
        set(x, y, 30, 60, 95, 255);
      }
    }
  }
  return encodePng(S, S, px);
}

const targets = [
  ['mipmap-mdpi', 48],
  ['mipmap-hdpi', 72],
  ['mipmap-xhdpi', 96],
  ['mipmap-xxhdpi', 144],
  ['mipmap-xxxhdpi', 192],
];

const base = path.resolve(__dirname, '..', 'app', 'src', 'main', 'res');
for (const [dir, size] of targets) {
  const out = path.join(base, dir);
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'ic_launcher.png'), draw(size));
  console.log('wrote', path.join(dir, 'ic_launcher.png'), size);
}
