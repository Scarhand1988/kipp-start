/*
 * Kipp-Start Web (iPhone-Version): dein Foto im Vollbild, das sich beim Kippen bewegt,
 * weich wird und dunkler zurücktritt – mit derselben Rechnung wie der Android-Hintergrund
 * (siehe kipp-math.js). Alles bleibt auf dem Gerät: kein Server, keine Analyse.
 */
(function () {
  'use strict';

  const K = window.KippMath;
  const { MotionMath, SoftFocus, Effects, DrawMath } = K;
  const VERSION = 'Web 1.0';
  const ANDROID_URL = 'https://github.com/Scarhand1988/kipp-start/releases/latest';
  const MAX_SIDE = 2600;          // längste Fotoseite nach dem Laden (reicht für 3x-Displays mit Zoom)
  const SYSTEM_SCALE = MotionMath.DEFAULT_SYSTEM_SCALE; // wie der Android-Hintergrund in Ruhe
  const BACKGROUND = '#101418';

  // ---------- Elemente ----------
  const $ = id => document.getElementById(id);
  const canvas = $('stage');
  const ctx = canvas.getContext('2d', { alpha: false });
  const welcome = $('welcome');
  const sheet = $('sheet');
  const fileInput = $('file');
  const motionButton = $('motion');
  const adjustBar = $('adjustBar');
  const adjustHint = $('adjustHint');
  const toast = $('toast');

  // ---------- Einstellungen (wie in der Android-App) ----------
  const DEFAULTS = { zoom: 1, x: 0, y: 0, blur: SoftFocus.DEFAULT_STRENGTH, enabled: true, frost: true, unlock: true, hold: true };
  const settings = Object.assign({}, DEFAULTS);
  function loadSettings() {
    try {
      const raw = localStorage.getItem('kipp.settings');
      if (raw) Object.assign(settings, JSON.parse(raw));
    } catch (e) { /* privater Modus o. Ä.: Voreinstellungen */ }
  }
  function saveSettings() {
    try { localStorage.setItem('kipp.settings', JSON.stringify(settings)); } catch (e) { /* egal */ }
  }

  // ---------- Fotospeicher (IndexedDB, nur auf dem Gerät) ----------
  function openDb() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open('kipp-start', 1);
      request.onupgradeneeded = () => request.result.createObjectStore('photo');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  async function storePhoto(blob) {
    try {
      const db = await openDb();
      await new Promise((resolve, reject) => {
        const tx = db.transaction('photo', 'readwrite');
        tx.objectStore('photo').put(blob, 'current');
        tx.oncomplete = resolve; tx.onerror = () => reject(tx.error);
      });
      db.close();
      if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
    } catch (e) { showToast('Foto konnte nicht gespeichert werden. Es bleibt bis zum Schließen sichtbar.'); }
  }
  async function readPhoto() {
    try {
      const db = await openDb();
      const blob = await new Promise((resolve, reject) => {
        const request = db.transaction('photo').objectStore('photo').get('current');
        request.onsuccess = () => resolve(request.result || null);
        request.onerror = () => reject(request.error);
      });
      db.close();
      return blob;
    } catch (e) { return null; }
  }

  // ---------- Foto vorbereiten ----------
  let photo = null;      // Canvas mit dem scharfen Foto
  let soft = null;       // Canvas mit der weichen Fassung in 1/4 Größe
  let softSource = null; // ImageData in 1/4 Größe (Vorlage für neue Stärken)

  async function decode(blob) {
    if (window.createImageBitmap) {
      try { return await createImageBitmap(blob, { imageOrientation: 'from-image' }); } catch (e) { /* weiter */ }
      try { return await createImageBitmap(blob); } catch (e) { /* weiter */ }
    }
    const url = URL.createObjectURL(blob);
    try {
      const img = new Image();
      img.src = url;
      await img.decode();
      return img;
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  }

  function preparePhoto(source) {
    const w = source.width || source.naturalWidth, h = source.height || source.naturalHeight;
    const scale = Math.min(1, MAX_SIDE / Math.max(w, h));
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w * scale));
    c.height = Math.max(1, Math.round(h * scale));
    const g = c.getContext('2d');
    g.imageSmoothingQuality = 'high';
    g.drawImage(source, 0, 0, c.width, c.height);
    if (source.close) source.close();
    // Vorlage für die weiche Fassung: 1/4 Größe wie SoftFocus.SCALE
    const s = document.createElement('canvas');
    s.width = Math.max(1, Math.round(c.width / SoftFocus.SCALE));
    s.height = Math.max(1, Math.round(c.height / SoftFocus.SCALE));
    const sg = s.getContext('2d');
    sg.imageSmoothingQuality = 'high';
    sg.drawImage(c, 0, 0, s.width, s.height);
    softSource = sg.getImageData(0, 0, s.width, s.height);
    photo = c;
    buildSoft();
  }

  /** Weiche Fassung neu rechnen (Regler auf 0: Öffnen-Animation nutzt die Voreinstellung, wie Android). */
  function buildSoft() {
    if (!softSource) { soft = null; return; }
    const strength = settings.blur > 0 ? settings.blur : SoftFocus.DEFAULT_STRENGTH;
    const data = new Uint8ClampedArray(softSource.data);
    SoftFocus.blur(data, softSource.width, softSource.height, SoftFocus.radius(strength), SoftFocus.PASSES);
    const c = soft && soft.width === softSource.width && soft.height === softSource.height ? soft : document.createElement('canvas');
    c.width = softSource.width; c.height = softSource.height;
    c.getContext('2d').putImageData(new ImageData(data, softSource.width, softSource.height), 0, 0);
    soft = c;
  }

  async function usePhoto(blob, isNew) {
    let source;
    try {
      source = await decode(blob);
    } catch (e) {
      showToast('Dieses Bild konnte nicht geöffnet werden. Wähle ein anderes.');
      return false;
    }
    preparePhoto(source);
    if (isNew) {
      settings.zoom = DEFAULTS.zoom; settings.x = DEFAULTS.x; settings.y = DEFAULTS.y;
      saveSettings();
      photo.toBlob(b => { if (b) storePhoto(b); }, 'image/jpeg', 0.92);
    }
    welcome.hidden = true;
    resize();
    startUnlock();
    await ensureMotion(false);
    return true;
  }

  // ---------- Bewegung ----------
  const tracker = new K.TiltTracker();
  let targetX = 0, targetY = 0, x = 0, y = 0;
  let heldTarget = 0, held = 0, heldDirX = 0, heldDirY = 0;
  let sensorSeen = false;
  let listening = false;
  let motionGranted = false; // Bewegungsdaten freigegeben (oder ohne Freigabe verfügbar)
  let motionDenied = false;  // abgelehnt: Antippen öffnet dann wieder die Einstellungen
  let baseResetPending = true;
  const needsPermission = typeof DeviceOrientationEvent !== 'undefined'
    && typeof DeviceOrientationEvent.requestPermission === 'function';

  function screenAngle() {
    if (screen.orientation && typeof screen.orientation.angle === 'number') return screen.orientation.angle;
    return typeof window.orientation === 'number' ? window.orientation : 0;
  }

  function onOrientation(e) {
    if (e.beta == null && e.gamma == null) return;
    if (!sensorSeen) { sensorSeen = true; motionButton.hidden = true; }
    if (!listening || !settings.enabled || adjusting) return;
    if (baseResetPending) { baseResetPending = false; tracker.reset(); tracker.resetBase(); }
    const q = K.toScreen(K.quaternionFromEuler(e.alpha, e.beta, e.gamma), screenAngle());
    const t = (e.timeStamp > 0 ? e.timeStamp : performance.now()) / 1000;
    tracker.update(q, t);
    targetX = tracker.x();
    targetY = tracker.y();
    heldTarget = settings.hold ? tracker.held() : 0;
    heldDirX = tracker.heldX();
    heldDirY = tracker.heldY();
    if (Math.abs(x - targetX) >= 0.0015 || Math.abs(y - targetY) >= 0.0015 || Math.abs(held - heldTarget) >= 0.0015) schedule();
  }

  function startListening() {
    if (listening) return;
    listening = true;
    targetX = targetY = 0;
    window.addEventListener('deviceorientation', onOrientation);
  }

  /** Sensor einschalten; auf dem iPhone braucht es dafür einmal ein Antippen und „Erlauben“. */
  async function ensureMotion(fromTap) {
    if (!needsPermission) { motionGranted = true; startListening(); watchForSensor(); return; }
    try {
      const state = await DeviceOrientationEvent.requestPermission();
      if (state === 'granted') { motionGranted = true; motionButton.hidden = true; startListening(); watchForSensor(); return; }
      motionDenied = true;
      motionButton.hidden = true;
      showToast('Kippen ist nicht erlaubt. Schließe die App ganz, öffne sie neu und tippe auf „Erlauben“.', 6000);
    } catch (e) {
      // Ohne Antippen darf Safari nicht fragen: Knopf zeigen
      if (photo) motionButton.hidden = false;
      if (fromTap) showToast('Safari hat die Bewegungsdaten nicht freigegeben.', 5000);
    }
  }

  function watchForSensor() {
    setTimeout(() => {
      if (!sensorSeen && photo) showToast('Keine Bewegungsdaten: Dieses Gerät oder dieser Browser liefert keine Lage.', 5000);
    }, 2500);
  }

  // ---------- Öffnen-Animation (wie die Entsperr-Animation auf Android) ----------
  let unlocking = false, unlockStart = 0, unlockAmount = 0;
  function startUnlock() {
    if (!settings.enabled || !settings.unlock || !photo) { schedule(); return; }
    unlocking = true; unlockStart = 0; unlockAmount = 1;
    schedule();
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      baseResetPending = true; // nächstes Öffnen = neue Ausgangshaltung
      return;
    }
    heldTarget = 0;
    targetX = targetY = 0;
    startUnlock();
  });

  // ---------- Bildtakt ----------
  let pending = false, lastFrame = 0;
  function schedule() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(frame);
  }

  function frame(now) {
    pending = false;
    const seconds = lastFrame === 0 ? 0.012 : Math.min(0.04, (now - lastFrame) / 1000);
    lastFrame = now;
    const tx = settings.enabled && !adjusting ? targetX : 0;
    const ty = settings.enabled && !adjusting ? targetY : 0;
    const th = settings.enabled && !adjusting ? heldTarget : 0;
    x = MotionMath.approach(x, tx, seconds);
    y = MotionMath.approach(y, ty, seconds);
    held = MotionMath.approach(held, th, seconds);
    if (unlocking) {
      if (unlockStart === 0) unlockStart = now;
      unlockAmount = Effects.unlockAmount(now - unlockStart);
      if (unlockAmount <= 0) { unlockAmount = 0; unlocking = false; }
    }
    const moving = x !== tx || y !== ty || held !== th || unlocking;
    draw();
    if (moving) schedule(); else lastFrame = 0;
  }

  // ---------- Zeichnen (wie TiltWallpaperService.draw) ----------
  let width = 0, height = 0;
  const slope = document.createElement('canvas');
  const slopeCtx = slope.getContext('2d');

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    width = Math.max(1, Math.round(canvas.clientWidth * dpr));
    height = Math.max(1, Math.round(canvas.clientHeight * dpr));
    if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
    slope.width = Math.max(1, Math.ceil(width / SoftFocus.SCALE));
    slope.height = Math.max(1, Math.ceil(height / SoftFocus.SCALE));
    draw();
  }

  function bounds() {
    return MotionMath.cover(width, height, photo.width, photo.height,
      settings.zoom, settings.x, settings.y, x, y, SYSTEM_SCALE);
  }

  function draw() {
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    ctx.fillStyle = BACKGROUND;
    ctx.fillRect(0, 0, width, height);
    if (!photo) return;
    const enabled = settings.enabled && !adjusting;
    const b = bounds();
    const dw = b[2] - b[0], dh = b[3] - b[1];

    const tiltMove = Math.max(Math.abs(x), Math.abs(y));
    const heldWins = held > tiltMove;
    const movement = Math.min(1, Math.max(tiltMove, held));
    const unlock = enabled && unlocking ? unlockAmount : 0;
    const tiltSoft = SoftFocus.alpha(movement, settings.blur);
    const evenSoft = Effects.softAlpha(0, unlock);
    const dark = Effects.dim(enabled && settings.frost ? Effects.frostDim(movement) : 0, unlock);
    const dir = heldWins ? Effects.blurSide(heldDirX, heldDirY) : Effects.blurSide(x, y);

    if (unlock > 0) {
      // von etwas tiefer und vergrößert in die Ruhelage „anheben“
      const s = 1 + Effects.UNLOCK_SCALE * unlock;
      const lift = Effects.UNLOCK_LIFT * unlock * height;
      ctx.setTransform(s, 0, 0, s, width / 2 * (1 - s), height / 2 * (1 - s) + lift);
    }
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(photo, b[0], b[1], dw, dh);

    if (soft) {
      const useSlope = tiltSoft > evenSoft && (dir[0] !== 0 || dir[1] !== 0);
      if (useSlope) {
        const low = Math.min(1, Math.max(evenSoft, tiltSoft * Effects.AWAY_SOFT));
        if (low > 0) { ctx.globalAlpha = low; ctx.drawImage(soft, b[0], b[1], dw, dh); }
        const second = DrawMath.secondLayer(low, tiltSoft);
        if (second > 0) drawSlope(b, dir, second);
      } else {
        const alpha = Math.min(1, Math.max(evenSoft, tiltSoft));
        if (alpha > 0) { ctx.globalAlpha = alpha; ctx.drawImage(soft, b[0], b[1], dw, dh); }
      }
    }
    // Farben verblassen: entspricht dem Farbfilter der Android-App (Bild · (1 − a) + Grau · a)
    const level = DrawMath.dimLevel(dark);
    if (level > 0) {
      const c = Effects.FROST_RGB;
      ctx.globalAlpha = level / 255;
      ctx.fillStyle = `rgb(${c[0]},${c[1]},${c[2]})`;
      ctx.fillRect(b[0], b[1], dw, dh);
    }
    ctx.globalAlpha = 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
  }

  /** Weiche Fassung mit Verlauf: hintere Seite durchsichtig, vordere Seite voll (Schicht 2 der Android-App). */
  function drawSlope(b, dir, alpha) {
    const f = 1 / SoftFocus.SCALE;
    const g = slopeCtx;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.globalCompositeOperation = 'source-over';
    g.globalAlpha = 1;
    g.clearRect(0, 0, slope.width, slope.height);
    g.drawImage(soft, b[0] * f, b[1] * f, (b[2] - b[0]) * f, (b[3] - b[1]) * f);
    const cx = width / 2, cy = height / 2;
    const reach = Math.max(1, Math.abs(dir[0]) * width / 2 + Math.abs(dir[1]) * height / 2);
    const gradient = g.createLinearGradient(
      (cx - dir[0] * reach) * f, (cy - dir[1] * reach) * f,
      (cx + dir[0] * reach) * f, (cy + dir[1] * reach) * f);
    gradient.addColorStop(0, 'rgba(255,255,255,0)');
    gradient.addColorStop(1, 'rgba(255,255,255,1)');
    g.globalCompositeOperation = 'destination-in';
    g.fillStyle = gradient;
    g.fillRect(0, 0, slope.width, slope.height);
    g.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = alpha;
    ctx.drawImage(slope, 0, 0, slope.width, slope.height, 0, 0, slope.width / f, slope.height / f);
  }

  // ---------- Bildausschnitt mit dem Finger ----------
  let adjusting = false;
  const pointers = new Map();
  let pinchDistance = 0;
  let downPoint = null;

  function setAdjusting(on) {
    adjusting = on;
    adjustBar.hidden = !on;
    adjustHint.hidden = !on;
    if (on) { closeSheet(); toast.hidden = true; }
    else saveSettings();
    schedule();
  }

  function distance() {
    const p = [...pointers.values()];
    return Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y);
  }

  canvas.addEventListener('pointerdown', e => {
    try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* ohne Fangen geht es auch */ }
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 1) downPoint = { x: e.clientX, y: e.clientY, t: e.timeStamp };
    if (pointers.size === 2) pinchDistance = distance();
  });

  canvas.addEventListener('pointermove', e => {
    const last = pointers.get(e.pointerId);
    if (!last) return;
    const dx = e.clientX - last.x, dy = e.clientY - last.y;
    last.x = e.clientX; last.y = e.clientY;
    if (!adjusting || !photo) return;
    const dpr = width / Math.max(1, canvas.clientWidth);
    if (pointers.size === 1) {
      const rx = DrawMath.range(width, height, photo.width, photo.height, settings.zoom, SYSTEM_SCALE, true);
      const ry = DrawMath.range(width, height, photo.width, photo.height, settings.zoom, SYSTEM_SCALE, false);
      settings.x = DrawMath.drag(settings.x, dx * dpr, rx);
      settings.y = DrawMath.drag(settings.y, dy * dpr, ry);
    } else if (pointers.size === 2) {
      const d = distance();
      if (pinchDistance > 0) settings.zoom = DrawMath.pinch(settings.zoom, d / pinchDistance);
      pinchDistance = d;
    }
    schedule();
  });

  function pointerEnd(e) {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinchDistance = 0;
    if (e.type !== 'pointerup' || !downPoint || adjusting) { if (!pointers.size) downPoint = null; return; }
    const moved = Math.hypot(e.clientX - downPoint.x, e.clientY - downPoint.y);
    const quick = e.timeStamp - downPoint.t < 500;
    downPoint = null;
    if (moved < 12 && quick && photo) {
      if (!motionGranted && !motionDenied) { ensureMotion(true); return; }
      toggleSheet();
    }
  }
  canvas.addEventListener('pointerup', pointerEnd);
  canvas.addEventListener('pointercancel', pointerEnd);

  // ---------- Einstellungen ----------
  const blurSlider = $('blurSlider');
  const blurLabel = $('blurLabel');
  const toggles = { enabled: $('tEnabled'), hold: $('tHold'), frost: $('tFrost'), unlock: $('tUnlock') };

  function blurText(value) {
    const percent = Math.round(value * 100);
    return percent === 0 ? 'Unschärfe beim Kippen: aus' : `Unschärfe beim Kippen: ${percent} %`;
  }

  function syncControls() {
    blurSlider.value = Math.round(settings.blur * 100);
    blurLabel.textContent = blurText(settings.blur);
    for (const key in toggles) toggles[key].checked = !!settings[key];
  }

  let softTimer = 0;
  blurSlider.addEventListener('input', () => {
    settings.blur = Number(blurSlider.value) / 100;
    blurLabel.textContent = blurText(settings.blur);
    saveSettings();
    clearTimeout(softTimer);
    softTimer = setTimeout(() => { buildSoft(); schedule(); }, 120);
  });

  for (const key in toggles) {
    toggles[key].addEventListener('change', () => {
      settings[key] = toggles[key].checked;
      saveSettings();
      if (key === 'hold' && !settings.hold) heldTarget = 0;
      if (key === 'enabled' && settings.enabled) { baseResetPending = true; }
      schedule();
    });
  }

  function openSheet() { syncControls(); sheet.hidden = false; requestAnimationFrame(() => sheet.classList.add('open')); }
  function closeSheet() { sheet.classList.remove('open'); setTimeout(() => { if (!sheet.classList.contains('open')) sheet.hidden = true; }, 220); }
  function toggleSheet() { if (sheet.hidden || !sheet.classList.contains('open')) openSheet(); else closeSheet(); }

  $('closeSheet').addEventListener('click', closeSheet);
  $('pick').addEventListener('click', () => fileInput.click());
  $('pickAgain').addEventListener('click', () => fileInput.click());
  $('adjust').addEventListener('click', () => setAdjusting(true));
  $('adjustDone').addEventListener('click', () => setAdjusting(false));
  $('adjustReset').addEventListener('click', () => {
    settings.zoom = DEFAULTS.zoom; settings.x = DEFAULTS.x; settings.y = DEFAULTS.y;
    schedule();
  });
  motionButton.addEventListener('click', () => ensureMotion(true));

  fileInput.addEventListener('change', async () => {
    const file = fileInput.files && fileInput.files[0];
    fileInput.value = '';
    if (!file) return;
    closeSheet();
    showToast('Foto wird geladen …');
    if (await usePhoto(file, true)) {
      showToast(motionButton.hidden ? 'Foto geladen. Antippen öffnet die Einstellungen.'
        : 'Foto geladen. Tippe auf „Kippen aktivieren“ und dann auf „Erlauben“.', 4500);
    }
  });

  let toastTimer = 0;
  function showToast(text, ms) {
    toast.textContent = text;
    toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toast.hidden = true; }, ms || 3200);
  }

  // ---------- Hinweise je nach Gerät ----------
  const ua = navigator.userAgent || '';
  const isIOS = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const isAndroid = /Android/i.test(ua);
  const standalone = navigator.standalone === true || (window.matchMedia && matchMedia('(display-mode: standalone)').matches);
  document.querySelectorAll('.install-hint').forEach(el => { el.hidden = !(isIOS && !standalone); });
  document.querySelectorAll('.android-hint').forEach(el => { el.hidden = !isAndroid; });
  document.querySelectorAll('.android-link').forEach(el => { el.href = ANDROID_URL; });
  $('version').textContent = `Kipp-Start ${VERSION}`;

  // ---------- Start ----------
  window.addEventListener('resize', resize);
  if (screen.orientation && screen.orientation.addEventListener) screen.orientation.addEventListener('change', () => { tracker.reset(); resize(); });
  loadSettings();
  syncControls();
  resize();
  readPhoto().then(blob => {
    if (blob) usePhoto(blob, false);
    else welcome.hidden = false;
  });
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
  }
})();
