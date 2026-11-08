/*
 * Kipp-Start Web: Rechenlogik, 1:1 übertragen aus der Android-App
 * (MotionMath, TiltTracker, SoftFocus, Effects, DrawMath). Kein Zugriff auf Browser-Funktionen,
 * damit sie in tests/check.mjs mit Node gegen die Werte der Android-App geprüft werden kann.
 */
(function (root) {
  'use strict';

  const toRad = Math.PI / 180;
  const clamp01 = v => Math.max(0, Math.min(1, v));

  // ---------- MotionMath ----------
  const MotionMath = {
    DEAD_ZONE: 0.65 * toRad,
    FULL_TILT: 23 * toRad,
    SHIFT_X: 0.024,
    SHIFT_Y: 0.018,
    DEFAULT_SYSTEM_SCALE: 1.10,

    tilt(radians) {
      const m = Math.min(1, Math.max(0,
        (Math.abs(radians) - MotionMath.DEAD_ZONE) / (MotionMath.FULL_TILT - MotionMath.DEAD_ZONE)));
      return m === 0 ? 0 : (radians < 0 ? -m : m);
    },

    approach(current, target, seconds) {
      const blend = 1 - Math.exp(-seconds / 0.019);
      const next = current + (target - current) * blend;
      return Math.abs(next - target) < 0.0015 ? target : next;
    },

    /** Bildausschnitt wie MotionMath.cover: [links, oben, rechts, unten]. */
    cover(width, height, imageWidth, imageHeight, zoom, positionX, positionY, tiltX, tiltY, extraScale) {
      const baseScale = Math.max(width / imageWidth, height / imageHeight) * zoom * Math.max(1, extraScale);
      const shortSide = Math.min(width, height);
      const reserveX = shortSide * MotionMath.SHIFT_X;
      const reserveY = shortSide * MotionMath.SHIFT_Y;
      const rangeX = Math.max(0, (imageWidth * baseScale - width) * 0.5 - reserveX);
      const rangeY = Math.max(0, (imageHeight * baseScale - height) * 0.5 - reserveY);
      const x = rangeX * positionX + reserveX * tiltX;
      const y = rangeY * positionY + reserveY * tiltY;
      const scale = Math.max(baseScale, Math.max(
        (width + 2 * Math.abs(x)) / imageWidth,
        (height + 2 * Math.abs(y)) / imageHeight));
      const halfWidth = imageWidth * scale * 0.5;
      const halfHeight = imageHeight * scale * 0.5;
      const centerX = width * 0.5 + x;
      const centerY = height * 0.5 + y;
      return [centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight];
    }
  };

  // ---------- TiltTracker ----------
  const SNAP_COS = 0.5;
  const FOLLOW_SECONDS = 1.5;

  function normalize(q) {
    const n = Math.hypot(q[0], q[1], q[2], q[3]);
    if (n < 1e-6) { q[0] = q[1] = q[2] = 0; q[3] = 1; return; }
    for (let i = 0; i < 4; i++) q[i] /= n;
  }

  /** Wie SensorManager.getRotationMatrixFromVector (3x3, zeilenweise). q = [x, y, z, w]. */
  function matrix(q, r) {
    const q1 = q[0], q2 = q[1], q3 = q[2], q0 = q[3];
    const sqQ1 = 2 * q1 * q1, sqQ2 = 2 * q2 * q2, sqQ3 = 2 * q3 * q3;
    const q1q2 = 2 * q1 * q2, q3q0 = 2 * q3 * q0, q1q3 = 2 * q1 * q3;
    const q2q0 = 2 * q2 * q0, q2q3 = 2 * q2 * q3, q1q0 = 2 * q1 * q0;
    r[0] = 1 - sqQ2 - sqQ3; r[1] = q1q2 - q3q0;     r[2] = q1q3 + q2q0;
    r[3] = q1q2 + q3q0;     r[4] = 1 - sqQ1 - sqQ3; r[5] = q2q3 - q1q0;
    r[6] = q1q3 - q2q0;     r[7] = q2q3 + q1q0;     r[8] = 1 - sqQ1 - sqQ2;
  }

  /** rel = from^T * to */
  function relative(from, to, rel) {
    for (let row = 0; row < 3; row++) {
      for (let col = 0; col < 3; col++) {
        rel[row * 3 + col] = from[row] * to[col] + from[row + 3] * to[col + 3] + from[row + 6] * to[col + 6];
      }
    }
  }

  function heldTilt(rel) {
    if (rel[8] < SNAP_COS) return 1;
    const pitch = Math.asin(Math.max(-1, Math.min(1, -rel[7])));
    const roll = Math.atan2(-rel[6], rel[8]);
    return Math.max(Math.abs(MotionMath.tilt(roll)), Math.abs(MotionMath.tilt(pitch)));
  }

  class TiltTracker {
    constructor() {
      this.ref = [0, 0, 0, 1];
      this.cur = [0, 0, 0, 1];
      this.refM = new Array(9).fill(0);
      this.curM = new Array(9).fill(0);
      this.rel = new Array(9).fill(0);
      this.baseM = new Array(9).fill(0);
      this.heldRel = new Array(9).fill(0);
      this.baseReady = false;
      this.heldValue = 0; this.heldXValue = 0; this.heldYValue = 0;
      this.ready = false;
      this.lastSeconds = 0;
      this.targetX = 0; this.targetY = 0;
    }

    /** Neu ansetzen (z. B. wenn die App wieder sichtbar wird). Die Ausgangshaltung bleibt. */
    reset() { this.ready = false; this.lastSeconds = 0; this.targetX = this.targetY = 0; }

    /** Ausgangshaltung („beim Entsperren“, hier: beim Öffnen) mit dem nächsten Wert neu festlegen. */
    resetBase() { this.baseReady = false; this.heldValue = this.heldXValue = this.heldYValue = 0; }

    held() { return this.heldValue; }
    heldX() { return this.heldXValue; }
    heldY() { return this.heldYValue; }
    x() { return this.targetX; }
    y() { return this.targetY; }

    /** q = [x, y, z, w] (Drehung Gerät → Welt), timeSeconds = Zeitstempel in Sekunden. */
    update(q, timeSeconds) {
      const cur = this.cur;
      cur[0] = q[0]; cur[1] = q[1]; cur[2] = q[2]; cur[3] = q[3];
      normalize(cur);
      matrix(cur, this.curM);
      this.updateHeld();

      if (!this.ready) {
        for (let i = 0; i < 4; i++) this.ref[i] = cur[i];
        this.ready = true;
        this.lastSeconds = timeSeconds;
        this.targetX = this.targetY = 0;
        return;
      }
      const seconds = Math.min(0.1, Math.max(0, timeSeconds - this.lastSeconds));
      this.lastSeconds = timeSeconds;

      const ref = this.ref;
      if (ref[0] * cur[0] + ref[1] * cur[1] + ref[2] * cur[2] + ref[3] * cur[3] < 0) {
        for (let i = 0; i < 4; i++) cur[i] = -cur[i];
      }
      matrix(ref, this.refM);
      matrix(cur, this.curM);
      relative(this.refM, this.curM, this.rel);
      const rel = this.rel;

      if (rel[8] < SNAP_COS) {
        for (let i = 0; i < 4; i++) ref[i] = cur[i];
        this.targetX = this.targetY = 0;
        return;
      }
      const pitch = Math.asin(Math.max(-1, Math.min(1, -rel[7])));
      const roll = Math.atan2(-rel[6], rel[8]);
      this.targetX = MotionMath.tilt(-roll);
      this.targetY = MotionMath.tilt(pitch);

      const k = 1 - Math.exp(-seconds / FOLLOW_SECONDS);
      for (let i = 0; i < 4; i++) ref[i] += (cur[i] - ref[i]) * k;
      normalize(ref);
    }

    updateHeld() {
      if (!this.baseReady) {
        for (let i = 0; i < 9; i++) this.baseM[i] = this.curM[i];
        this.baseReady = true;
        this.heldValue = this.heldXValue = this.heldYValue = 0;
        return;
      }
      const hr = this.heldRel;
      relative(this.baseM, this.curM, hr);
      this.heldValue = heldTilt(hr);
      if (hr[8] < SNAP_COS) {
        this.heldXValue = this.heldYValue = 0;
      } else {
        this.heldXValue = MotionMath.tilt(-Math.atan2(-hr[6], hr[8]));
        this.heldYValue = MotionMath.tilt(Math.asin(Math.max(-1, Math.min(1, -hr[7]))));
      }
    }
  }

  /**
   * Lage aus dem Browser-Ereignis „deviceorientation“ (alpha, beta, gamma in Grad, Reihenfolge Z-X'-Y'')
   * als Quaternion [x, y, z, w] im selben Koordinatensystem wie der Android-Rotationsvektor.
   */
  function quaternionFromEuler(alpha, beta, gamma) {
    const x = (beta || 0) * toRad, y = (gamma || 0) * toRad, z = (alpha || 0) * toRad;
    const cX = Math.cos(x / 2), cY = Math.cos(y / 2), cZ = Math.cos(z / 2);
    const sX = Math.sin(x / 2), sY = Math.sin(y / 2), sZ = Math.sin(z / 2);
    return [
      sX * cY * cZ - cX * sY * sZ,
      cX * sY * cZ + sX * cY * sZ,
      cX * cY * sZ + sX * sY * cZ,
      cX * cY * cZ - sX * sY * sZ
    ];
  }

  /**
   * Bildschirm gedreht (Querformat): Gerätekoordinaten in Bildschirmkoordinaten drehen, indem die
   * Gerätelage um die Bildschirmnormale gedreht wird. angle in Grad wie screen.orientation.angle.
   */
  function toScreen(q, angle) {
    const a = ((angle || 0) % 360 + 360) % 360;
    if (a === 0) return q;
    const h = -a * toRad / 2;           // Drehung um die z-Achse des Geräts
    const bz = Math.sin(h), bw = Math.cos(h);
    // q * b mit b = [0, 0, bz, bw]
    return [
      q[0] * bw + q[1] * bz,
      q[1] * bw - q[0] * bz,
      q[2] * bw + q[3] * bz,
      q[3] * bw - q[2] * bz
    ];
  }

  // ---------- SoftFocus ----------
  const SoftFocus = {
    SCALE: 4,
    PASSES: 3,
    DEFAULT_STRENGTH: 0.7,
    radius(strength) { return Math.round(2 + 10 * clamp01(strength)); },
    maxAlpha(strength) { const s = clamp01(strength); return s <= 0 ? 0 : 0.5 + 0.5 * s; },
    alpha(movement, strength) {
      const m = Math.min(1, Math.abs(movement));
      if (strength <= 0 || m <= 0.025) return 0;
      return SoftFocus.maxAlpha(strength) * Math.pow(m, 1.5);
    },
    /** Weichzeichnen an Ort und Stelle, RGBA-Bytes wie ImageData.data (gleiches Verfahren wie Android). */
    blur(data, width, height, radius, passes) {
      if (radius < 1 || width < 1 || height < 1) return;
      const temp = new Uint8ClampedArray(data.length);
      for (let p = 0; p < passes; p++) {
        box(data, temp, height, width, radius, width, 1);
        box(temp, data, width, height, radius, 1, width);
      }
    }
  };

  function box(src, dst, lines, length, radius, lineStep, step) {
    const window = 2 * radius + 1;
    const half = window >> 1;
    const last = length - 1;
    for (let line = 0; line < lines; line++) {
      const base = line * lineStep;
      let a = 0, r = 0, g = 0, b = 0;
      for (let i = -radius; i <= radius; i++) {
        const o = (base + (i < 0 ? 0 : (i > last ? last : i)) * step) * 4;
        r += src[o]; g += src[o + 1]; b += src[o + 2]; a += src[o + 3];
      }
      for (let i = 0; i < length; i++) {
        const d = (base + i * step) * 4;
        dst[d] = ((r + half) / window) | 0;
        dst[d + 1] = ((g + half) / window) | 0;
        dst[d + 2] = ((b + half) / window) | 0;
        dst[d + 3] = ((a + half) / window) | 0;
        const io = i - radius, ii = i + radius + 1;
        const o = (base + (io < 0 ? 0 : (io > last ? last : io)) * step) * 4;
        const n = (base + (ii < 0 ? 0 : (ii > last ? last : ii)) * step) * 4;
        r += src[n] - src[o]; g += src[n + 1] - src[o + 1];
        b += src[n + 2] - src[o + 2]; a += src[n + 3] - src[o + 3];
      }
    }
  }

  // ---------- Effects ----------
  const Effects = {
    UNLOCK_MS: 450,
    UNLOCK_SCALE: 0.08,
    UNLOCK_LIFT: 0.03,
    UNLOCK_DIM: 0.45,
    FROST_DIM: 0.72,
    AWAY_SOFT: 0.30,
    FROST_RGB: [27, 26, 26],
    ease(progress) { const p = clamp01(progress); const q = 1 - p; return 1 - q * q * q; },
    unlockAmount(elapsedMs) { return elapsedMs <= 0 ? 1 : 1 - Effects.ease(elapsedMs / Effects.UNLOCK_MS); },
    frostDim(movement) {
      const m = Math.min(1, Math.abs(movement));
      if (m <= 0.025) return 0;
      return Effects.FROST_DIM * Math.pow(m, 1.2);
    },
    blurSide(tiltX, tiltY) {
      const length = Math.hypot(tiltX, tiltY);
      if (length < 0.02) return [0, 0];
      return [tiltX / length, tiltY / length];
    },
    dim(frost, unlock) { return Math.max(frost, Effects.UNLOCK_DIM * unlock); },
    softAlpha(tilt, unlock) { return Math.min(1, Math.max(tilt, unlock)); }
  };

  // ---------- DrawMath ----------
  const DrawMath = {
    ZOOM_MAX: 1.7,
    secondLayer(low, high) {
      const l = clamp01(low), h = clamp01(high);
      if (h <= l || l >= 0.999) return 0;
      return (h - l) / (1 - l);
    },
    stacked(first, second) { return first + (1 - first) * second; },
    dimLevel(dark) { return Math.round(255 * clamp01(dark)); },
    range(width, height, imageWidth, imageHeight, zoom, extraScale, horizontal) {
      const baseScale = Math.max(width / imageWidth, height / imageHeight) * zoom * Math.max(1, extraScale);
      const shortSide = Math.min(width, height);
      if (horizontal) return Math.max(0, (imageWidth * baseScale - width) * 0.5 - shortSide * MotionMath.SHIFT_X);
      return Math.max(0, (imageHeight * baseScale - height) * 0.5 - shortSide * MotionMath.SHIFT_Y);
    },
    drag(position, deltaPixels, range) {
      if (range <= 0) return position;
      return Math.max(-1, Math.min(1, position + deltaPixels / range));
    },
    pinch(zoom, factor) {
      if (!(factor > 0)) return zoom;
      return Math.max(1, Math.min(DrawMath.ZOOM_MAX, zoom * factor));
    }
  };

  const api = { MotionMath, TiltTracker, SoftFocus, Effects, DrawMath, quaternionFromEuler, toScreen, matrix, clamp01 };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.KippMath = api;
})(typeof self !== 'undefined' ? self : this);
