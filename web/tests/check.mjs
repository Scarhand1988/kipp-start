// Prüft die Rechenlogik der iPhone-Web-App gegen Referenzwerte der Android-App.
// Die Referenzwerte erzeugt Golden.java mit den Android-Klassen MotionMath, TiltTracker, SoftFocus,
// Effects und DrawMath (siehe Workflow pages.yml).
// Aufruf: node web/tests/check.mjs <golden.json>
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const K = require(join(here, '..', 'kipp-math.js'));
const golden = JSON.parse(readFileSync(process.argv[2] || join(here, 'golden.json'), 'utf8'));

let checks = 0, failures = 0;
function ok(condition, message) {
  checks++;
  if (!condition) { failures++; console.error('FEHLER: ' + message); }
}
function near(a, b, tol, message) {
  ok(Math.abs(a - b) <= tol, `${message}: ${a} statt ${b}`);
}

// 1) Kipp-Rechnung Schritt für Schritt wie in der Android-App (float dort, double hier)
{
  const t = new K.TiltTracker();
  golden.tracker.forEach((row, i) => {
    const [qx, qy, qz, qw, nanos, x, y, held, hx, hy] = row;
    t.update([qx, qy, qz, qw], nanos / 1e9);
    near(t.x(), x, 2e-4, `Schritt ${i} x`);
    near(t.y(), y, 2e-4, `Schritt ${i} y`);
    near(t.held(), held, 2e-4, `Schritt ${i} held`);
    near(t.heldX(), hx, 2e-4, `Schritt ${i} heldX`);
    near(t.heldY(), hy, 2e-4, `Schritt ${i} heldY`);
    if (i === 260) t.resetBase();
    if (i === 330) t.reset();
  });
}

// 2) Bildausschnitt
golden.cover.forEach((c, i) => {
  const b = K.MotionMath.cover(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9]);
  for (let k = 0; k < 4; k++) near(b[k], c[10 + k], 0.05, `Ausschnitt ${i}.${k}`);
});

// 3) Kurven für Unschärfe, Abdunklung, Öffnen-Animation, Verlauf, Kippwert, Radius
golden.curves.forEach(row => {
  const m = row[0];
  near(K.SoftFocus.alpha(m, 0.7), row[1], 1e-5, `Unschärfe 70 % bei ${m}`);
  near(K.SoftFocus.alpha(m, 0.3), row[2], 1e-5, `Unschärfe 30 % bei ${m}`);
  near(K.Effects.frostDim(m), row[3], 1e-5, `Abdunklung bei ${m}`);
  near(K.Effects.unlockAmount(m * 500), row[4], 1e-5, `Öffnen-Animation bei ${m}`);
  near(K.DrawMath.secondLayer(m * 0.4, m), row[5], 1e-5, `Verlauf-Schicht bei ${m}`);
  near(K.MotionMath.tilt((m * 30 - 5) * Math.PI / 180), row[6], 1e-5, `Kippwert bei ${m}`);
  near(K.SoftFocus.radius(m), row[7], 0, `Radius bei ${m}`);
});

// 4) Weichzeichner: Byte für Byte gleich
{
  const g = golden.blur;
  const data = new Uint8ClampedArray(g.w * g.h * 4);
  g.src.forEach((c, i) => {
    const u = c >>> 0;
    data[i * 4] = (u >>> 16) & 255; data[i * 4 + 1] = (u >>> 8) & 255;
    data[i * 4 + 2] = u & 255; data[i * 4 + 3] = u >>> 24;
  });
  K.SoftFocus.blur(data, g.w, g.h, g.radius, g.passes);
  let same = 0;
  g.dst.forEach((c, i) => {
    const u = c >>> 0;
    if (data[i * 4] === ((u >>> 16) & 255) && data[i * 4 + 1] === ((u >>> 8) & 255)
        && data[i * 4 + 2] === (u & 255) && data[i * 4 + 3] === (u >>> 24)) same++;
  });
  ok(same === g.dst.length, `Weichzeichner: ${same} von ${g.dst.length} Pixeln gleich`);
}

// 5) Browser-Lage (alpha, beta, gamma) ergibt dieselbe Drehung wie Rz(alpha)·Rx(beta)·Ry(gamma)
function mul(a, b) {
  const r = new Array(9).fill(0);
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) for (let k = 0; k < 3; k++) r[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j];
  return r;
}
function euler(alpha, beta, gamma) {
  const a = alpha * Math.PI / 180, b = beta * Math.PI / 180, g = gamma * Math.PI / 180;
  const rz = [Math.cos(a), -Math.sin(a), 0, Math.sin(a), Math.cos(a), 0, 0, 0, 1];
  const rx = [1, 0, 0, 0, Math.cos(b), -Math.sin(b), 0, Math.sin(b), Math.cos(b)];
  const ry = [Math.cos(g), 0, Math.sin(g), 0, 1, 0, -Math.sin(g), 0, Math.cos(g)];
  return mul(mul(rz, rx), ry);
}
for (const [a, b, g] of [[0, 0, 0], [30, 70, -20], [200, -40, 80], [359, 89, -89], [10, 120, 5]]) {
  const m = new Array(9);
  K.matrix(K.quaternionFromEuler(a, b, g), m);
  const e = euler(a, b, g);
  for (let i = 0; i < 9; i++) near(m[i], e[i], 1e-9, `Lage ${a}/${b}/${g} Feld ${i}`);
}

// 6) Richtung wie in der Android-App: rechte Kante nach hinten → x < 0, obere Kante nach hinten → y > 0
function qmul(p, q) {
  return [
    p[3] * q[0] + p[0] * q[3] + p[1] * q[2] - p[2] * q[1],
    p[3] * q[1] - p[0] * q[2] + p[1] * q[3] + p[2] * q[0],
    p[3] * q[2] + p[0] * q[1] - p[1] * q[0] + p[2] * q[3],
    p[3] * q[3] - p[0] * q[0] - p[1] * q[1] - p[2] * q[2]
  ];
}
function axis(x, y, z, degrees) {
  const h = degrees * Math.PI / 360;
  return [x * Math.sin(h), y * Math.sin(h), z * Math.sin(h), Math.cos(h)];
}
function tiltAfter(turn) {
  const t = new K.TiltTracker();
  const start = K.quaternionFromEuler(0, 70, 0); // aufrecht in der Hand
  t.update(start, 0);
  t.update(qmul(start, turn), 0.016);
  return [t.x(), t.y()];
}
{
  const [x1] = tiltAfter(axis(0, 1, 0, 12));   // um die Längsachse: rechte Kante geht nach hinten
  ok(x1 < -0.3, `rechte Kante nach hinten ergibt x < 0 (ist ${x1})`);
  const [, y1] = tiltAfter(axis(1, 0, 0, -12)); // obere Kante geht nach hinten
  ok(y1 > 0.3, `obere Kante nach hinten ergibt y > 0 (ist ${y1})`);
}

// 7) Querformat: dieselbe Bewegung zur Bildschirmseite ergibt denselben Kippwert wie im Hochformat
{
  const start = K.quaternionFromEuler(0, 70, 0);
  // Gerät 90° gegen den Uhrzeigersinn gedreht (Bildschirm-Winkel 90): die Bildschirm-Rechte ist die Geräte-Unterseite (-y)
  const landscape = qmul(start, axis(0, 0, 1, 90));
  const t = new K.TiltTracker();
  t.update(K.toScreen(landscape, 90), 0);
  // rechte Bildschirmkante nach hinten = Drehung um die Bildschirm-Hochachse (Geräte-x)
  t.update(K.toScreen(qmul(landscape, axis(1, 0, 0, 12)), 90), 0.016);
  ok(t.x() < -0.3 && Math.abs(t.y()) < 0.05, `Querformat: rechte Kante nach hinten ergibt x < 0 (x ${t.x()}, y ${t.y()})`);
}

// 8) Ausschnitt mit dem Finger und zwei Fingern
near(K.DrawMath.drag(0, 50, 100), 0.5, 1e-9, 'Verschieben');
near(K.DrawMath.drag(0.9, 50, 100), 1, 1e-9, 'Verschieben begrenzt');
near(K.DrawMath.pinch(1.5, 2), 1.7, 1e-9, 'Zoom begrenzt');
near(K.DrawMath.pinch(1.2, 0.5), 1, 1e-9, 'Zoom unten begrenzt');

console.log(`WebCheck: ${checks - failures} von ${checks} Prüfungen bestanden`);
if (failures) process.exit(1);
